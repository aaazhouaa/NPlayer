package dev.anilbeesetti.nextplayer.core.media.services

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HiddenVideoScannerTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var scanner: HiddenVideoScanner

    @Before
    fun setUp() {
        ShadowMediaMetadataRetriever.reset()
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(context = SupervisorJob() + Dispatchers.Default)
        scanner = HiddenVideoScanner(
            context = context,
            applicationScope = scope,
        )
        root = createTempDirectory("hidden-video-scanner").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `finds hidden files and hidden directories but skips normal media`() = runBlocking {
        val hiddenFile = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        val hiddenDir = File(
            root,
            ".Movies",
        ).apply { mkdirs() }
        val videoInHiddenDir = File(
            hiddenDir,
            "episode.mkv",
        ).apply { writeText("video") }
        val noMediaDir = File(
            root,
            "private",
        ).apply { mkdirs() }
        val videoInNoMediaDir = File(
            noMediaDir,
            "clip.mov",
        ).apply { writeText("video") }
        File(
            noMediaDir,
            ".nomedia",
        ).apply { writeText("") }
        File(
            root,
            "normal.mp4",
        ).apply { writeText("video") }
        File(
            root,
            ".notes.txt",
        ).apply { writeText("not a video") }

        val result = scanner.scan(root.absolutePath)

        val paths = result.map { it.path }.toSet()
        assertEquals(
            3,
            result.size,
        )
        assertTrue(paths.contains(hiddenFile.path))
        assertTrue(paths.contains(videoInHiddenDir.path))
        assertTrue(paths.contains(videoInNoMediaDir.path))
        assertTrue(result.all { it.isHidden })
    }

    @Test
    fun `scanned videos report dates in seconds like mediastore rows`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == video.path }
        // Seconds, not milliseconds: Video.isNew and date sorting consume MediaStore's units.
        assertEquals(
            video.lastModified() / 1000L,
            mediaVideo.dateModified,
        )
    }

    @Test
    fun `first discovered videos report discovery time as dateAdded`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply {
            writeText("video")
            // A copied file keeps its original mtime, which must not decide "newly added".
            setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L)
        }

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == video.path }
        assertTrue(
            "dateAdded was ${mediaVideo.dateAdded}",
            mediaVideo.dateAdded * 1000L > System.currentTimeMillis() - 60_000L,
        )
    }

    @Test
    fun `records seeded by an older version are rediscovered`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        scanner.scan(root.absolutePath)
        // Simulate a record written before discovery time became the seeding rule.
        val record = File(
            context.filesDir,
            "hidden_first_seen.tsv",
        )
        record.writeText(
            "${video.absolutePath}\t${System.currentTimeMillis() / 1000L - 30L * 24 * 60 * 60}",
        )
        scanner.invalidate()

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == video.path }
        assertTrue(
            "dateAdded was ${mediaVideo.dateAdded}",
            mediaVideo.dateAdded * 1000L > System.currentTimeMillis() - 60_000L,
        )
    }

    @Test
    fun `stale scan cache is re-walked so newly added files appear`() = runBlocking {
        File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        // Files added to a hidden folder never reach MediaStore, so no change notification
        // refreshes the cache; only the freshness window (or pull-to-refresh) can.
        val lateVideo = File(
            root,
            ".late.mp4",
        ).apply { writeText("video") }
        val cacheFile = context.filesDir.listFiles()!!.single { it.name.startsWith("hidden_scan_") }
        cacheFile.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)

        val reloaded = HiddenVideoScanner(
            context = context,
            applicationScope = scope,
        ).scan(root.absolutePath)

        assertTrue(reloaded.any { it.path == lateVideo.path })
    }

    @Test
    fun `expired snapshot still supplies durations without parsing files again`() = runBlocking {
        val first = File(
            root,
            ".first.mp4",
        ).apply { writeText("video") }
        val second = File(
            root,
            ".second.mp4",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = first.absolutePath,
            duration = "111000",
            width = "1920",
            height = "1080",
        )
        addVideoMetadata(
            path = second.absolutePath,
            duration = "222000",
            width = "1280",
            height = "720",
        )
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        // A cold start more than the freshness window later: the directory has to be walked
        // again to notice new files, but the durations parsed in the earlier session are still
        // valid, so they must be returned by the very first emission of the new process.
        context.filesDir.listFiles()!!
            .single { it.name.startsWith("hidden_scan_") }
            .setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        // Forget every registered metadata entry: a file opened again would now read as zero,
        // so a non-zero duration below proves the value was reused instead of re-parsed.
        ShadowMediaMetadataRetriever.reset()

        val coldScanner = HiddenVideoScanner(
            context = context,
            applicationScope = scope,
        )
        val result = coldScanner.scan(root.absolutePath)

        assertEquals(
            111_000L,
            result.single { it.path == first.path }.duration,
        )
        assertEquals(
            222_000L,
            result.single { it.path == second.path }.duration,
        )
    }

    @Test
    fun `newly discovered files report discovery time as dateAdded`() = runBlocking {
        File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        scanner.scan(root.absolutePath)

        val lateVideo = File(
            root,
            ".late.mp4",
        ).apply {
            writeText("video")
            // A copied file keeps its original mtime, which must not decide "newly added".
            setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L)
        }
        scanner.invalidate()

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == lateVideo.path }
        assertTrue(
            "dateAdded was ${mediaVideo.dateAdded}",
            mediaVideo.dateAdded * 1000L > System.currentTimeMillis() - 60_000L,
        )
    }

    @Test
    fun `respecting nomedia excludes directories with nomedia marker`() = runBlocking {
        val hiddenFile = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        val noMediaDir = File(
            root,
            "private",
        ).apply { mkdirs() }
        File(
            noMediaDir,
            "clip.mov",
        ).apply { writeText("video") }
        File(
            noMediaDir,
            ".nomedia",
        ).apply { writeText("") }

        val result = scanner.scan(
            root.absolutePath,
            respectNoMedia = true,
        )

        val paths = result.map { it.path }.toSet()
        assertEquals(
            1,
            result.size,
        )
        assertTrue(paths.contains(hiddenFile.path))
        assertTrue(result.none { it.path.startsWith(noMediaDir.path) })
    }

    @Test
    fun `finds videos in multi-level hidden folders`() = runBlocking {
        val hiddenRoot = File(
            root,
            ".Movies",
        ).apply { mkdirs() }
        val seasonDir = File(
            hiddenRoot,
            "Season1",
        ).apply { mkdirs() }
        val videoInSeason = File(
            seasonDir,
            "episode.mkv",
        ).apply { writeText("video") }
        val directVideo = File(
            hiddenRoot,
            "movie.mp4",
        ).apply { writeText("video") }

        val result = scanner.scan(hiddenRoot.absolutePath)

        val paths = result.map { it.path }.toSet()
        assertEquals(
            2,
            result.size,
        )
        assertTrue(paths.contains(videoInSeason.path))
        assertTrue(paths.contains(directVideo.path))
        assertTrue(result.all { it.isHidden })
    }

    @Test
    fun `finds videos when entering a non-dot child of a hidden ancestor`() = runBlocking {
        val hiddenRoot = File(
            root,
            ".Movies",
        ).apply { mkdirs() }
        val seasonDir = File(
            hiddenRoot,
            "Season1",
        ).apply { mkdirs() }
        val deeperDir = File(
            seasonDir,
            "Sub",
        ).apply { mkdirs() }
        val videoInDeeperDir = File(
            deeperDir,
            "episode.mkv",
        ).apply { writeText("video") }

        // Simulate the user navigating into `.Movies/Season1` (root itself is not dot-named).
        val result = scanner.scan(seasonDir.absolutePath)

        val paths = result.map { it.path }.toSet()
        assertEquals(
            1,
            result.size,
        )
        assertTrue(paths.contains(videoInDeeperDir.path))
        assertTrue(result.all { it.isHidden })
    }

    @Test
    fun `reads duration and resolution from hidden video files`() = runBlocking {
        val hiddenDir = File(
            root,
            ".Movies",
        ).apply { mkdirs() }
        val video = File(
            hiddenDir,
            "episode.mkv",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "600000",
            width = "1920",
            height = "1080",
        )

        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == video.path }
        assertEquals(
            600_000L,
            mediaVideo.duration,
        )
        assertEquals(
            1920,
            mediaVideo.width,
        )
        assertEquals(
            1080,
            mediaVideo.height,
        )
    }

    @Test
    @Config(sdk = [33])
    fun `reads metadata on api levels that close the retriever`() {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "61000",
            width = "1280",
            height = "720",
        )

        val metadata = video.readVideoMetadata()

        assertEquals(
            61_000L,
            metadata?.duration,
        )
        assertEquals(
            1280,
            metadata?.width,
        )
        assertEquals(
            720,
            metadata?.height,
        )
    }

    @Test
    fun `files without readable metadata keep zeroed fields`() = runBlocking {
        val video = File(
            root,
            ".broken.mp4",
        ).apply { writeText("not a video") }

        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        val result = scanner.scan(root.absolutePath)

        val mediaVideo = result.single { it.path == video.path }
        assertEquals(
            0L,
            mediaVideo.duration,
        )
        assertEquals(
            0,
            mediaVideo.width,
        )
        assertEquals(
            0,
            mediaVideo.height,
        )
    }

    @Test
    fun `scan emits the list before metadata enrichment finishes`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "42000",
            width = "1920",
            height = "1080",
        )

        // The first emission is complete except for metadata, which arrives from the
        // background pass only after the list has been returned.
        val first = scanner.scan(root.absolutePath)
        assertEquals(
            0L,
            first.single().duration,
        )

        awaitEnrichment(scanner)

        val enriched = scanner.scan(root.absolutePath)
        assertEquals(
            42_000L,
            enriched.single().duration,
        )
    }

    @Test
    fun `subfolder scan reuses metadata parsed for the parent folder`() = runBlocking {
        val hiddenRoot = File(
            root,
            ".Movies",
        ).apply { mkdirs() }
        val seasonDir = File(
            hiddenRoot,
            "Season1",
        ).apply { mkdirs() }
        val video = File(
            seasonDir,
            "episode.mkv",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "600000",
            width = "1920",
            height = "1080",
        )

        scanner.scan(hiddenRoot.absolutePath)
        awaitEnrichment(scanner)

        // Navigating into the subfolder must not parse the same files again: the first
        // emission already carries the duration parsed during the parent scan.
        val seasonVideos = scanner.scan(seasonDir.absolutePath)

        val mediaVideo = seasonVideos.single()
        assertEquals(
            600_000L,
            mediaVideo.duration,
        )
        assertEquals(
            1920,
            mediaVideo.width,
        )
        assertEquals(
            1080,
            mediaVideo.height,
        )
    }

    @Test
    fun `replaced files are parsed again`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "1000",
            width = "640",
            height = "480",
        )
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        // Same path, different size and modification time: the cached entry is stale.
        video.writeText("a much longer video")
        video.setLastModified(video.lastModified() + 5_000)
        addVideoMetadata(
            path = video.absolutePath,
            duration = "2000",
            width = "1280",
            height = "720",
        )
        scanner.invalidate()

        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        val result = scanner.scan(root.absolutePath)
        assertEquals(
            2_000L,
            result.single().duration,
        )
    }

    @Test
    fun `persisted scan cache restores duration and resolution`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        addVideoMetadata(
            path = video.absolutePath,
            duration = "123456",
            width = "3840",
            height = "2160",
        )
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)
        val firstSeen = scanner.scan(root.absolutePath).single().dateAdded

        // A fresh scanner has an empty in-memory cache and reads the persisted scan instead.
        val reloaded = HiddenVideoScanner(
            context = context,
            applicationScope = scope,
        ).scan(root.absolutePath)

        val mediaVideo = reloaded.single()
        assertEquals(
            video.path,
            mediaVideo.path,
        )
        assertEquals(
            firstSeen,
            mediaVideo.dateAdded,
        )
        assertEquals(
            123_456L,
            mediaVideo.duration,
        )
        assertEquals(
            3840,
            mediaVideo.width,
        )
        assertEquals(
            2160,
            mediaVideo.height,
        )
    }

    @Test
    fun `subfolder scan reuses metadata restored from disk with millisecond modification time`() =
        runBlocking {
            val hiddenDir = File(
                root,
                ".Movies",
            ).apply { mkdirs() }
            val video = File(
                hiddenDir,
                "episode.mp4",
            ).apply {
                writeText("video")
                setLastModified(1_700_000_000_123L)
            }
            File(
                context.filesDir,
                "hidden_scan_${root.absolutePath.hashCode()}_false.txt",
            ).writeText("${video.absolutePath}\t123456\t1920\t1080")
            val restored = scanner.scan(root.absolutePath).single()
            assertEquals(
                123_456L,
                restored.duration,
            )

            val nested = scanner.scan(hiddenDir.absolutePath).single()

            assertEquals(
                restored.duration,
                nested.duration,
            )
            assertEquals(
                restored.width,
                nested.width,
            )
            assertEquals(
                restored.height,
                nested.height,
            )
        }

    @Test
    fun `scan cache without persisted metadata is discarded`() = runBlocking {
        val video = File(
            root,
            ".hidden.mp4",
        ).apply { writeText("video") }
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        // Rewrite the cache in the format used before metadata was persisted.
        val cacheFile = context.filesDir.listFiles()!!.single { it.name.startsWith("hidden_scan_") }
        cacheFile.writeText(video.path)
        addVideoMetadata(
            path = video.absolutePath,
            duration = "999000",
            width = "640",
            height = "480",
        )

        val reloadedScanner = HiddenVideoScanner(
            context = context,
            applicationScope = scope,
        )
        reloadedScanner.scan(root.absolutePath)
        awaitEnrichment(reloadedScanner)
        val result = reloadedScanner.scan(root.absolutePath)

        val mediaVideo = result.single()
        assertEquals(
            999_000L,
            mediaVideo.duration,
        )
        assertEquals(
            640,
            mediaVideo.width,
        )
        assertEquals(
            480,
            mediaVideo.height,
        )
    }

    /** Waits until the background metadata pass replaced the cached scan. */
    @Test
    fun `deleted hidden files disappear from cached scans immediately`() = runBlocking {
        val kept = File(
            root,
            ".kept.mp4",
        ).apply { writeText("video") }
        val deleted = File(
            root,
            ".deleted.mp4",
        ).apply { writeText("video") }
        scanner.scan(root.absolutePath)
        awaitEnrichment(scanner)

        assertTrue(deleted.delete())
        scanner.forgetDeletedFiles(listOf(deleted.absolutePath))

        // Nothing in the file system changed as far as MediaStore is concerned: it never had a
        // row for either file. Without an explicit invalidation the deleted entry stays in the
        // snapshot for the rest of the freshness window and the row plays a gone file.
        val result = scanner.scan(root.absolutePath)

        assertEquals(
            listOf(kept.path),
            result.map { it.path },
        )
    }

    @Test
    fun `deleting every hidden file leaves an empty scan instead of the cached entries`() =
        runBlocking {
            val video = File(
                root,
                ".hidden.mp4",
            ).apply { writeText("video") }
            scanner.scan(root.absolutePath)
            awaitEnrichment(scanner)

            assertTrue(video.delete())
            scanner.forgetDeletedFiles(listOf(video.absolutePath))

            assertTrue(scanner.scan(root.absolutePath).isEmpty())
        }

    private suspend fun awaitEnrichment(
        target: HiddenVideoScanner,
        scanRoot: String? = root.absolutePath,
        timeoutMillis: Long = 5_000,
    ) {
        val initial = target.scan(scanRoot)
        withTimeout(timeoutMillis) {
            while (target.scan(scanRoot) === initial) {
                delay(25.milliseconds)
            }
        }
    }

    private fun addVideoMetadata(path: String, duration: String, width: String, height: String) {
        ShadowMediaMetadataRetriever.addMetadata(
            path,
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            duration,
        )
        ShadowMediaMetadataRetriever.addMetadata(
            path,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            width,
        )
        ShadowMediaMetadataRetriever.addMetadata(
            path,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            height,
        )
    }
}
