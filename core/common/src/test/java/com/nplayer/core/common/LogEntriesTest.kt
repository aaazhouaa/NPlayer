package com.nplayer.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEntriesTest {

    @Test
    fun entriesAreClassifiedByTheirLevelMarker() {
        val entries = parseLogEntries(
            text = """
                2026-01-02 03:04:05.006 I/Logger: session started
                2026-01-02 03:04:06.007 D/Playback: first frame
                2026-01-02 03:04:07.008 W/Playback: slow decode
                2026-01-02 03:04:08.009 E/Crash: uncaught exception
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                LogLevel.INFO,
                LogLevel.DEBUG,
                LogLevel.WARNING,
                LogLevel.ERROR,
            ),
            entries.map { it.level },
        )
        assertEquals(
            4,
            entries.size,
        )
    }

    @Test
    fun stackTraceLinesStayWithTheEntryThatProducedThem() {
        val entries = parseLogEntries(
            text = """
                2026-01-02 03:04:08.009 E/Crash: uncaught exception
                java.lang.IllegalStateException: boom
                	at com.nplayer.Crash.run(Crash.kt:1)
                2026-01-02 03:04:09.010 I/Logger: next entry
            """.trimIndent(),
        )

        assertEquals(
            2,
            entries.size,
        )
        assertEquals(
            LogLevel.ERROR,
            entries[0].level,
        )
        assertTrue(entries[0].text.contains(other = "IllegalStateException: boom"))
        assertTrue(entries[0].text.contains(other = "Crash.kt:1"))
        assertEquals(
            LogLevel.INFO,
            entries[1].level,
        )
    }

    @Test
    fun unclassifiedHeadIsKeptAsAnEntryWithoutLevel() {
        val entries = parseLogEntries(
            text = """
                stray line from a truncated file
                2026-01-02 03:04:05.006 I/Logger: session started
            """.trimIndent(),
        )

        assertEquals(
            2,
            entries.size,
        )
        assertNull(entries[0].level)
        assertTrue(entries[0].text.contains(other = "stray line"))
        assertEquals(
            LogLevel.INFO,
            entries[1].level,
        )
    }

    @Test
    fun blankTextHasNoEntries() {
        assertEquals(
            emptyList<LogEntry>(),
            parseLogEntries(text = ""),
        )
        assertEquals(
            emptyList<LogEntry>(),
            parseLogEntries(text = "\n\n"),
        )
    }

    @Test
    fun filterKeepsSelectedLevelsAndUnclassifiedEntries() {
        val entries = parseLogEntries(
            text = """
                head without level
                2026-01-02 03:04:05.006 I/Logger: info
                2026-01-02 03:04:06.007 D/Logger: debug
                2026-01-02 03:04:07.008 E/Logger: error
            """.trimIndent(),
        )

        val filtered = entries.filterByLevels(
            levels = setOf(
                LogLevel.ERROR,
                LogLevel.INFO,
            ),
        )

        assertEquals(
            listOf(
                null,
                LogLevel.INFO,
                LogLevel.ERROR,
            ),
            filtered.map { it.level },
        )
    }
}
