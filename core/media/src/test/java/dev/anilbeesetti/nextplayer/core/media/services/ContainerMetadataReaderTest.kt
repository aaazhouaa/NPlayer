package dev.anilbeesetti.nextplayer.core.media.services

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies the container index reader against real files.
 *
 * The whole point of reading the index directly is that it must agree with what a platform
 * retriever reports, so the samples here are real muxed files rather than hand-built boxes.
 */
class ContainerMetadataReaderTest {

    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = createTempDirectory("container-metadata").toFile()
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private fun sample(name: String): File {
        val resource = requireNotNull(javaClass.classLoader?.getResource("containers/$name")) {
            "missing test resource containers/$name"
        }
        return File(resource.toURI())
    }

    @Test
    fun `reads duration and resolution from an mp4 whose moov is at the end`() {
        val metadata = requireNotNull(sample("thumbnail-test.mp4").readContainerMetadata())

        assertEquals(
            1_000L,
            metadata.duration,
        )
        assertEquals(
            160,
            metadata.width,
        )
        assertEquals(
            90,
            metadata.height,
        )
    }

    @Test
    fun `reads duration and resolution from a matroska file`() {
        val metadata = requireNotNull(sample("basic.srt.mkv").readContainerMetadata())

        assertEquals(
            24_021L,
            metadata.duration,
        )
        assertEquals(
            640,
            metadata.width,
        )
        assertEquals(
            360,
            metadata.height,
        )
    }

    @Test
    fun `unsupported extension reports nothing so the caller can fall back`() {
        val file = File(
            scratch,
            "video.unknown",
        ).apply { writeBytes(ByteArray(64)) }

        assertNull(file.readContainerMetadata())
    }

    @Test
    fun `truncated container reports nothing instead of throwing`() {
        val source = sample("thumbnail-test.mp4").readBytes()
        val file = File(
            scratch,
            "truncated.mp4",
        ).apply { writeBytes(source.copyOf(32)) }

        assertNull(file.readContainerMetadata())
    }

    @Test
    fun `empty file reports nothing`() {
        val file = File(
            scratch,
            "empty.mkv",
        ).apply { createNewFile() }

        assertNull(file.readContainerMetadata())
    }

    @Test
    fun `reads far more files per second than a platform retriever could open`() {
        val source = sample("basic.srt.mkv").readBytes()
        val files = (0 until 200).map { index ->
            File(
                scratch,
                "episode-$index.mkv",
            ).apply { writeBytes(source) }
        }

        val startedAt = System.nanoTime()
        val total = files.sumOf { file -> file.readContainerMetadata()?.duration ?: 0L }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // Summing a folder's durations is only cheap if opening each file is cheap: 200 files
        // have to stay inside a budget a full demuxer cannot meet.
        assertEquals(
            200 * 24_021L,
            total,
        )
        assertEquals(
            "reading 200 files took ${elapsedMillis}ms",
            true,
            elapsedMillis < 2_000,
        )
    }
}
