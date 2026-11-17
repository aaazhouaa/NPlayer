package com.nplayer.core.common.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilePathTest {

    @Test
    fun `internal storage volume root uses the user directory`() {
        assertEquals(
            "/storage/emulated/0",
            volumeRootOf(path = "/storage/emulated/0/Download/Movies/a.mp4"),
        )
        assertEquals(
            "/storage/emulated/10",
            volumeRootOf(path = "/storage/emulated/10/Movies/a.mp4"),
        )
    }

    @Test
    fun `removable volume root uses the volume id`() {
        assertEquals(
            "/storage/1A2B-3C4D",
            volumeRootOf(path = "/storage/1A2B-3C4D/Movies/a.mp4"),
        )
    }

    @Test
    fun `unrecognised layouts have no volume root`() {
        assertNull(volumeRootOf(path = "Movies/2024/a.mp4"))
        assertNull(volumeRootOf(path = "/data/local/tmp/a.mp4"))
        assertNull(volumeRootOf(path = ""))
    }

    @Test
    fun `readable path drops the internal storage prefix`() {
        assertEquals(
            "Download",
            "/storage/emulated/0/Download".readablePath,
        )
        assertEquals(
            "DCIM/Camera",
            "/storage/emulated/0/DCIM/Camera".readablePath,
        )
        assertEquals(
            "Movies/2024",
            "/storage/1A2B-3C4D/Movies/2024".readablePath,
        )
    }

    @Test
    fun `readable path names the storage root itself`() {
        assertEquals(
            "Internal Storage",
            "/storage/emulated/0".readablePath,
        )
        assertEquals(
            "Internal Storage",
            "/storage/emulated/0/".readablePath,
        )
    }

    @Test
    fun `readable path keeps paths outside a known volume unchanged`() {
        assertEquals(
            "Movies/2024",
            "Movies/2024".readablePath,
        )
        assertEquals(
            "/data/local/tmp",
            "/data/local/tmp".readablePath,
        )
        assertEquals(
            "",
            "".readablePath,
        )
    }
}
