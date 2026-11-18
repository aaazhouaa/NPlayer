package com.nplayer.core.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogTrimTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val marker = "[earlier entries dropped]"

    @Test
    fun trimKeepsTheNewestBytesAndDropsThePartialFirstLine() {
        val file = temporaryFolder.newFile("log-20260102-030405.log")
        val lines = (1..100).joinToString(separator = "") { index ->
            "2026-01-02 03:04:05.006 I/Logger: entry $index\n"
        }
        file.writeText(lines)

        val trimmed = trimLogFile(
            file = file,
            keepBytes = 500,
            marker = marker,
        )

        val content = file.readText()
        assertEquals(
            trimmed,
            file.length(),
        )
        assertTrue(content.startsWith(prefix = marker))
        // The newest entry is still there; the oldest ones are gone.
        assertTrue(content.contains(other = "entry 100"))
        assertFalse(content.contains(other = "entry 1\n"))
        // Every retained line is whole: the cut must not leave a fragment at the top.
        content.lineSequence()
            .filter { it.isNotBlank() && it != marker }
            .forEach { line ->
                assertTrue(
                    line,
                    line.startsWith(prefix = "2026-01-02 "),
                )
            }
    }

    @Test
    fun trimOfAWholeFileKeepsEveryLine() {
        val file = temporaryFolder.newFile("log-20260102-030405.log")
        file.writeText("first line\nsecond line\n")

        trimLogFile(
            file = file,
            keepBytes = 1024,
            marker = marker,
        )

        assertEquals(
            "$marker\nfirst line\nsecond line\n",
            file.readText(),
        )
    }

    @Test
    fun trimOfAMissingFileReportsFailure() {
        val file = File(
            temporaryFolder.root,
            "absent.log",
        )

        assertEquals(
            null,
            trimLogFile(
                file = file,
                keepBytes = 1024,
                marker = marker,
            ),
        )
    }

    @Test
    fun trimmingTwiceKeepsTheMarkerAndTheNewestContent() {
        val file = temporaryFolder.newFile("log-20260102-030405.log")
        repeat(times = 3) { pass ->
            file.appendText(
                (1..40).joinToString(separator = "") { index ->
                    "2026-01-02 03:04:05.006 I/Logger: pass $pass entry $index\n"
                },
            )
            trimLogFile(
                file = file,
                keepBytes = 400,
                marker = marker,
            )
        }

        val content = file.readText()
        assertTrue(content.startsWith(prefix = marker))
        // Only one marker survives, so repeated trimming does not pile up headers.
        assertEquals(
            1,
            content.lineSequence().count { it == marker },
        )
        assertTrue(content.contains(other = "pass 2"))
    }
}
