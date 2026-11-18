package com.nplayer.core.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun directory(): File = temporaryFolder.newFolder("logs")

    private fun store(
        directory: File,
        activeSession: ActiveLogSession = NoActiveSession,
    ): LogStore = LogStore(
        directory = directory,
        activeSession = activeSession,
    )

    /**
     * Stands in for [Logger]. A restart creates a differently named file, the way a fresh session
     * gets a new timestamp, and only for the session that is actually open.
     */
    private class RecordingActiveSession(active: File?) : ActiveLogSession {
        var activeFile: File? = active
        val restarts = mutableListOf<File>()

        override fun onSessionDeleted(from: File) {
            if (activeFile != from) return
            restarts += from
            activeFile = File(
                from.parentFile,
                "log-20261231-235959.log",
            ).apply { writeText(text = "") }
        }
    }

    private object NoActiveSession : ActiveLogSession {
        override fun onSessionDeleted(from: File) = Unit
    }

    @Test
    fun sessionsAreListedNewestFirstByStartTime() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260101-100000.log",
            text = "old\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260103-100000.log",
            text = "new\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260102-100000.log",
            text = "middle\n",
        )

        val sessions = store(directory = directory).sessions()

        assertEquals(
            listOf(
                "log-20260103-100000.log",
                "log-20260102-100000.log",
                "log-20260101-100000.log",
            ),
            sessions.map { it.name },
        )
    }

    @Test
    fun startTimeComesFromTheFileNameNotTheLastWrite() {
        val directory = directory()
        val file = writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "line\n",
        )
        // A session that ran for a while is modified long after it started.
        file.setLastModified(1_800_000_000_000L)

        val session = store(directory = directory).sessions().single()

        assertEquals(
            "2026-01-02 03:04:05",
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US,
            ).format(
                Date(session.startedAtMillis),
            ),
        )
    }

    @Test
    fun filesOutsideTheLoggerNamingSchemeAreIgnored() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "kept\n",
        )
        writeSession(
            directory = directory,
            name = "unrelated.log",
            text = "not ours\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260102-030405.txt",
            text = "not ours\n",
        )

        val sessions = store(directory = directory).sessions()

        assertEquals(
            listOf("log-20260102-030405.log"),
            sessions.map { it.name },
        )
    }

    @Test
    fun crashIsDetectedFromTheSessionTail() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "2026-01-02 03:04:05.006 I/Logger: session started\n" +
                "2026-01-02 03:04:06.007 E/Crash: uncaught exception\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "2026-01-01 03:04:05.006 I/Logger: session started\n",
        )

        val sessions = store(directory = directory).sessions()

        assertTrue(sessions.first { it.name == "log-20260102-030405.log" }.containsCrash)
        assertFalse(sessions.first { it.name == "log-20260101-030405.log" }.containsCrash)
    }

    @Test
    fun crashDetectionOnlyReadsTheTailOfALargeSession() {
        val directory = directory()
        // The marker sits beyond the scan window, so a bounded read must not report a crash.
        val padding = "x".repeat(n = LOG_CRASH_SCAN_BYTES) + "\n"
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "2026-01-02 03:04:06.007 E/Crash: head\n$padding",
        )

        val session = store(directory = directory).sessions().single()

        assertFalse(session.containsCrash)
    }

    @Test
    fun deleteRemovesOnlyTheGivenSession() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "a\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "b\n",
        )
        val logStore = store(directory = directory)

        logStore.delete(session = logStore.sessions().first())

        assertEquals(
            listOf("log-20260101-030405.log"),
            logStore.sessions().map { it.name },
        )
    }

    @Test
    fun deleteAllEmptiesTheStore() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "a\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "b\n",
        )
        val logStore = store(directory = directory)

        logStore.deleteAll()

        assertEquals(
            emptyList<LogSession>(),
            logStore.sessions(),
        )
    }

    @Test
    fun deleteOfTheActiveSessionRestartsItInsteadOfLeavingAnUnlinkedFile() {
        val directory = directory()
        val active = writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "a\n",
        )
        val activeSession = RecordingActiveSession(active = active)
        val logStore = store(
            directory = directory,
            activeSession = activeSession,
        )

        logStore.delete(session = logStore.sessions().first())

        assertEquals(
            listOf(active),
            activeSession.restarts,
        )
        assertEquals(
            listOf("log-20261231-235959.log"),
            logStore.sessions().map { it.name },
        )
    }

    @Test
    fun deleteOfAnOlderSessionLeavesTheActiveOneAlone() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "a\n",
        )
        val older = writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "b\n",
        )
        val active = File(
            directory,
            "log-20260103-030405.log",
        ).apply { writeText(text = "c\n") }
        val activeSession = RecordingActiveSession(active = active)
        val logStore = store(
            directory = directory,
            activeSession = activeSession,
        )

        logStore.delete(session = logStore.sessions().first { it.name == older.name })

        assertEquals(
            emptyList<File>(),
            activeSession.restarts,
        )
        assertSame(
            active,
            activeSession.activeFile,
        )
    }

    @Test
    fun deleteAllRestartsTheActiveSessionOnce() {
        val directory = directory()
        val active = writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "a\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "b\n",
        )
        val activeSession = RecordingActiveSession(active = active)
        val logStore = store(
            directory = directory,
            activeSession = activeSession,
        )

        logStore.deleteAll()

        assertEquals(
            listOf(active),
            activeSession.restarts,
        )
        assertEquals(
            listOf("log-20261231-235959.log"),
            logStore.sessions().map { it.name },
        )
    }

    @Test
    fun pruningKeepsTheNewestSessions() {
        val directory = directory()
        writeSession(
            directory = directory,
            name = "log-20260101-030405.log",
            text = "a\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260102-030405.log",
            text = "b\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260103-030405.log",
            text = "c\n",
        )
        writeSession(
            directory = directory,
            name = "log-20260104-030405.log",
            text = "d\n",
        )

        pruneLogSessions(
            directory = directory,
            keep = 2,
        )

        assertEquals(
            listOf(
                "log-20260104-030405.log",
                "log-20260103-030405.log",
            ),
            logSessionFiles(directory = directory).map { it.name },
        )
    }

    private fun writeSession(directory: File, name: String, text: String): File = File(
        directory,
        name,
    ).apply { writeText(text = text) }
}
