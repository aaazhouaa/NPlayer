package com.nplayer.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {

    @Test
    fun passwordInUriUserInfoIsRedactedAndTheUserKept() {
        val redacted = redactLogText(
            text = "opening smb://alice:s3cret@nas.local/share/clip.mkv",
        )

        assertEquals(
            "opening smb://alice@nas.local/share/clip.mkv",
            redacted,
        )
        assertFalse(redacted.contains(other = "s3cret"))
    }

    @Test
    fun emptyUserInfoUnderAUriIsRedacted() {
        val redacted = redactLogText(text = "https://:onlypassword@example.com/a.mkv")

        assertFalse(redacted.contains(other = "onlypassword"))
        assertTrue(redacted.contains(other = "https://***@example.com/a.mkv"))
    }

    @Test
    fun sensitiveQueryParametersAreRedacted() {
        val redacted = redactLogText(
            text = "webdav://example.com/a.mkv?token=abc123&size=42&Password=hunter2",
        )

        assertFalse(redacted.contains(other = "abc123"))
        assertFalse(redacted.contains(other = "hunter2"))
        assertTrue(redacted.contains(other = "token=***"))
        assertTrue(redacted.contains(other = "Password=***"))
        // A parameter that carries no secret has to survive: it is what makes the log useful.
        assertTrue(redacted.contains(other = "size=42"))
    }

    @Test
    fun textWithoutCredentialsIsUnchanged() {
        val text = "media scan found 12 videos for folder=/storage/emulated/0/Movies in 84ms"

        assertEquals(
            text,
            redactLogText(text = text),
        )
    }

    @Test
    fun plainLocalPathsAndVersionsAreNotMistakenForUserInfo() {
        val text = "ffmpeg frame in 210ms for /storage/emulated/0/a b/c.mkv (ffmpeg 6.1.1)"

        assertEquals(
            text,
            redactLogText(text = text),
        )
    }
}
