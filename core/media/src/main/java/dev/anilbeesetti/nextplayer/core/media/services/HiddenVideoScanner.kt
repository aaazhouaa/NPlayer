package dev.anilbeesetti.nextplayer.core.media.services

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.mapAsync
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Scans the file system directly for videos that MediaStore never indexes: files and directories
 * whose names start with a dot (`.hidden.mp4`, `.Movies/`) and directories containing a `.nomedia`
 * marker.
 *
 * On Android 10+ (API 29) the MediaStore `DATA`-based path view is still readable, but direct
 * `File` access to shared storage requires "All files access" (`MANAGE_EXTERNAL_STORAGE`).
 * Callers are responsible for only enabling this when the user has granted that permission.
 *
 * Files that MediaStore already indexes are skipped: they are already visible in the library and
 * must not be duplicated. The `file:` uri returned for genuinely hidden files is consumed directly
 * by the player.
 *
 * Duration and resolution are parsed in a background pass after the list is returned, so a slow
 * (or very large) hidden library never delays the picker. [observeCacheUpdates] emits once that
 * pass completes so lists can re-query.
 *
 * Files added to a hidden folder never reach MediaStore, so no change notification can refresh
 * the caches. Scans are therefore re-walked once the cached snapshot is older than
 * [SCAN_FRESHNESS_MILLIS], and every file's first discovery time is recorded so newly added
 * files are reported with the same "added" semantics as MediaStore rows.
 */
@Single
class HiddenVideoScanner(
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) {

    private data class ScanKey(val root: String?, val respectNoMedia: Boolean)

    private class CachedScan(val videos: List<MediaVideo>, val scannedAtMillis: Long) {
        fun isFresh(nowMillis: Long): Boolean = nowMillis - scannedAtMillis < SCAN_FRESHNESS_MILLIS
    }

    /** In-memory scan results keyed by root + .nomedia policy, avoiding repeated directory walks. */
    private val cache = ConcurrentHashMap<ScanKey, CachedScan>()

    /**
     * Epoch seconds at which each hidden file was first seen by the scanner, keyed by path.
     *
     * A file copied into a hidden folder keeps its original mtime, which would never read as
     * "recently added". Discovery time is what MediaStore reports as DATE_ADDED, so it is what
     * hidden files report too.
     */
    private val firstSeenByPath = ConcurrentHashMap<String, Long>()
    private var firstSeenLoaded = false

    /**
     * Metadata parsed per file, so navigating between a folder and its subfolders never parses
     * the same file twice. Entries are keyed by path and validated against the file's size and
     * modification time, so a replaced file is re-read.
     */
    private val metadataCache = ConcurrentHashMap<String, CachedFileMetadata>()

    /** Background metadata passes, one per scan key, so concurrent scans don't duplicate work. */
    private val enrichmentJobs = ConcurrentHashMap<ScanKey, Job>()

    private val cacheUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits when a background metadata pass finished, so the media list can re-query and pick
     * up durations that were still unknown when the list was first emitted.
     */
    fun observeCacheUpdates(): Flow<Unit> = cacheUpdates.asSharedFlow()

    /**
     * Returns video files found under [root] that MediaStore skips.
     *
     * Results are cached in memory and persisted to disk, so a cold start reuses the last scan
     * instead of walking the file system again. A cached snapshot older than
     * [SCAN_FRESHNESS_MILLIS] is re-walked, and [invalidate] (pull-to-refresh) drops both
     * caches immediately.
     *
     * @param root The absolute directory to scan, or null to scan every storage volume.
     * @param respectNoMedia When true, directories whose own name or any ancestor contains a
     *        `.nomedia` marker are excluded entirely (their media is not scanned).
     */
    suspend fun scan(root: String?, respectNoMedia: Boolean = false): List<MediaVideo> {
        if (!isFileSystemAccessible()) return emptyList()
        loadFirstSeen()

        val key = ScanKey(
            root,
            respectNoMedia,
        )
        val nowMillis = System.currentTimeMillis()
        cache[key]?.takeIf { it.isFresh(nowMillis = nowMillis) }?.let { return it.videos }
        loadFromDisk(
            key = key,
            nowMillis = nowMillis,
        )?.let { cached ->
            cache[key] = CachedScan(
                videos = cached,
                scannedAtMillis = nowMillis,
            )
            // Seed the per-file metadata so scans of subfolders reuse these values
            // instead of parsing the same files again.
            cached.forEach { video -> video.rememberMetadata() }
            return cached
        }

        val roots = root?.let(::listOf) ?: storageVolumeRoots()
        val mediaStorePaths = mediaStoreVideoPaths()

        val seen = mutableSetOf<String>()
        val files = roots.flatMap { rootPath ->
            val rootFile = File(rootPath)
            scanDirectory(
                directory = rootFile,
                mediaStorePaths = mediaStorePaths,
                seen = seen,
                respectNoMedia = respectNoMedia,
                isDirectoryHidden = rootFile.isHiddenPath(),
                ancestorHasNoMedia = rootFile.hasNoMediaAncestor(),
            )
        }

        // The list is emitted right away. Metadata already parsed for a file (an earlier
        // scan of a parent folder, a previous session's disk cache) is included immediately;
        // only genuinely new files wait for the background pass below.
        val videos = files.map { it.toMediaVideo(metadata = it.peekCachedVideoMetadata()) }
        cache[key] = CachedScan(
            videos = videos,
            scannedAtMillis = nowMillis,
        )
        persistFirstSeen(pruneMissingFiles = root == null)
        enrichInBackground(
            key = key,
            files = files,
        )
        return videos
    }

    /** Drops both the in-memory and persisted caches so the next [scan] walks the file system again. */
    fun invalidate() {
        enrichmentJobs.values.forEach { job -> job.cancel() }
        enrichmentJobs.clear()
        cache.clear()
        clearDiskCache()
        // The first-seen record deliberately survives: it captures when a file was discovered,
        // not the contents of any single scan.
    }

    private fun firstSeenRecordFile(): File = File(
        context.filesDir,
        "hidden_first_seen.tsv",
    )

    private fun loadFirstSeen() {
        if (firstSeenLoaded) return
        firstSeenLoaded = true
        val file = firstSeenRecordFile()
        if (!file.exists()) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        // Records written before discovery time became the seeding rule are discarded: they
        // were seeded from file timestamps, which cannot express "added to the library".
        if (lines.firstOrNull() != FIRST_SEEN_RECORD_HEADER) return
        lines.drop(n = 1).forEach { line ->
            val separator = line.indexOf('\t')
            if (separator <= 0) return@forEach
            val seconds = line.substring(separator + 1).trim().toLongOrNull() ?: return@forEach
            firstSeenByPath[
                line.substring(
                    0,
                    separator,
                ),
            ] = seconds
        }
    }

    private fun persistFirstSeen(pruneMissingFiles: Boolean) {
        if (pruneMissingFiles) {
            firstSeenByPath.keys
                .filter { path -> !File(path).exists() }
                .forEach { path -> firstSeenByPath.remove(path) }
        }
        runCatching {
            firstSeenRecordFile().writeText(
                buildString {
                    appendLine(FIRST_SEEN_RECORD_HEADER)
                    firstSeenByPath.entries.forEach { (path, seconds) ->
                        append(path)
                        append('\t')
                        appendLine(seconds)
                    }
                },
            )
        }
    }

    /**
     * Epoch seconds at which [path] was first seen, recording it on first discovery.
     *
     * A file copied into a hidden folder keeps its original mtime, which would never read as
     * "recently added"; discovery time is what MediaStore reports as DATE_ADDED, so it is what
     * hidden files report too. The cost is that a library revealed for the first time counts
     * as new until it ages past the threshold.
     */
    private fun firstSeenSecondsFor(path: String): Long {
        firstSeenByPath[path]?.let { return it }
        val discoveredAt = System.currentTimeMillis() / 1000L
        firstSeenByPath[path] = discoveredAt
        return discoveredAt
    }

    private data class CachedFileMetadata(
        val size: Long,
        val lastModified: Long,
        val metadata: FileVideoMetadata,
    )

    /**
     * Reads metadata for this file, reusing a previous parse while the file is unchanged.
     * Parses only on a miss, so this must not run on the list's critical path.
     */
    private fun File.readCachedVideoMetadata(): FileVideoMetadata? {
        peekCachedVideoMetadata()?.let { return it }
        val metadata = readVideoMetadata() ?: return null
        metadataCache[absolutePath] = CachedFileMetadata(
            size = length(),
            lastModified = lastModified(),
            metadata = metadata,
        )
        return metadata
    }

    /** Cache-only lookup: never opens the file, so the list can be built without waiting. */
    private fun File.peekCachedVideoMetadata(): FileVideoMetadata? {
        val cached = metadataCache[absolutePath]
        return cached
            ?.takeIf { it.size == length() && it.lastModified == lastModified() }
            ?.metadata
    }

    /** Stores already known metadata for this video's file. */
    private fun MediaVideo.rememberMetadata() {
        metadataCache[path] = CachedFileMetadata(
            size = size,
            lastModified = dateModified,
            metadata = FileVideoMetadata(
                duration = duration,
                width = width,
                height = height,
            ),
        )
    }

    private fun diskFile(key: ScanKey): File {
        val rootHash = key.root?.hashCode() ?: 0
        return File(
            context.filesDir,
            "hidden_scan_${rootHash}_${key.respectNoMedia}.txt",
        )
    }

    private fun saveToDisk(key: ScanKey, videos: List<MediaVideo>) {
        runCatching {
            diskFile(key).writeText(
                videos.joinToString(separator = "\n") { video ->
                    "${video.path}\t${video.duration}\t${video.width}\t${video.height}"
                },
            )
        }
    }

    private fun loadFromDisk(key: ScanKey, nowMillis: Long): List<MediaVideo>? {
        val file = diskFile(key)
        if (!file.exists()) return null
        // A snapshot past the freshness window is re-walked: files added to a hidden folder
        // never reach MediaStore, so no change notification can refresh the cache.
        if (nowMillis - file.lastModified() > SCAN_FRESHNESS_MILLIS) return null
        return runCatching {
            val lines = file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isNotEmpty() && '\t' !in lines.first()) {
                // Written before metadata was persisted: drop it so the next scan repopulates.
                file.delete()
                return null
            }
            val videos = lines.mapNotNull { line -> line.toCachedMediaVideo() }.toList()
            if (videos.isEmpty()) file.delete()
            videos
        }.getOrNull()
    }

    private fun clearDiskCache() {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("hidden_scan_") }
            ?.forEach { it.delete() }
    }

    /**
     * Reads duration and resolution for every scanned file in the background.
     *
     * Opening a MediaMetadataRetriever is expensive, so concurrency is bounded: a large hidden
     * library must not open all of its files at once. The cache and the disk snapshot are only
     * replaced once the whole pass finished, so a partially enriched list is never persisted.
     */
    private fun enrichInBackground(key: ScanKey, files: List<File>) {
        if (enrichmentJobs[key]?.isActive == true) return
        enrichmentJobs[key] = applicationScope.launch {
            try {
                val permits = Semaphore(MAX_CONCURRENT_METADATA_READS)
                val enriched = withContext(Dispatchers.IO) {
                    val videos = files.mapAsync { file ->
                        permits.withPermit {
                            file.toMediaVideo(metadata = file.readCachedVideoMetadata())
                        }
                    }
                    saveToDisk(
                        key,
                        videos,
                    )
                    videos
                }
                cache[key] = CachedScan(
                    videos = enriched,
                    scannedAtMillis = cache[key]?.scannedAtMillis ?: System.currentTimeMillis(),
                )
                cacheUpdates.tryEmit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The list is already visible with unknown durations; a failed pass must not
                // take the app down. The next scan retries.
                Logger.logError(
                    tag = TAG,
                    message = "Hidden media metadata enrichment failed: ${e.message}",
                )
            }
        }
    }

    private fun File.toMediaVideo(metadata: FileVideoMetadata?): MediaVideo {
        val canonicalPath = runCatching { canonicalPath }.getOrNull() ?: absolutePath
        return MediaVideo(
            id = canonicalPath.hashCode().toLong(),
            uri = toUri(),
            path = canonicalPath,
            title = name,
            parentPath = parent ?: "/",
            displayName = name,
            duration = metadata?.duration ?: 0L,
            size = length(),
            width = metadata?.width ?: 0,
            height = metadata?.height ?: 0,
            // Seconds, matching the MediaStore date units consumed by Video.isNew and sorting.
            dateModified = lastModified() / 1000L,
            dateAdded = firstSeenSecondsFor(path = canonicalPath),
            isHidden = true,
        )
    }

    /**
     * Parses a persisted `path\tduration\twidth\theight` line. Fields are read from the
     * right so paths that themselves contain tabs survive.
     */
    private fun String.toCachedMediaVideo(): MediaVideo? {
        var rest = this
        val height = rest.substringAfterLast('\t').toIntOrNull() ?: return null
        rest = rest.substringBeforeLast('\t')
        val width = rest.substringAfterLast('\t').toIntOrNull() ?: return null
        rest = rest.substringBeforeLast('\t')
        val duration = rest.substringAfterLast('\t').toLongOrNull() ?: return null
        val path = rest.substringBeforeLast('\t')
        val file = File(path).takeIf { it.isFile } ?: return null
        return MediaVideo(
            id = path.hashCode().toLong(),
            uri = file.toUri(),
            path = path,
            title = file.name,
            parentPath = file.parent ?: "/",
            displayName = file.name,
            duration = duration,
            size = file.length(),
            width = width,
            height = height,
            // Seconds, matching the MediaStore date units consumed by Video.isNew and sorting.
            dateModified = file.lastModified() / 1000L,
            dateAdded = firstSeenSecondsFor(path = path),
            isHidden = true,
        )
    }

    private fun scanDirectory(
        directory: File,
        mediaStorePaths: Set<String>,
        seen: MutableSet<String>,
        respectNoMedia: Boolean,
        isDirectoryHidden: Boolean = false,
        ancestorHasNoMedia: Boolean = false,
    ): List<File> {
        if (!directory.isDirectory) return emptyList()

        val results = mutableListOf<File>()
        val children = directory.listFiles() ?: return emptyList()
        val hasNoMediaMarker = children.any {
            it.name.equals(
                ".nomedia",
                ignoreCase = true,
            )
        }
        val effectiveNoMedia = ancestorHasNoMedia || hasNoMediaMarker

        // When respecting .nomedia, this directory is excluded together with its whole subtree.
        if (respectNoMedia && effectiveNoMedia) return emptyList()

        for (child in children) {
            if (child.isDirectory) {
                // A hidden or .nomedia directory hides its whole subtree, so propagate the hidden
                // state downward while descending into it. A non-hidden child under a hidden parent
                // must also inherit the parent's hidden state (multi-level hidden folders).
                val childHidden =
                    isDirectoryHidden || child.name.startsWith(".") || effectiveNoMedia
                results +=
                    scanDirectory(
                        child,
                        mediaStorePaths,
                        seen,
                        respectNoMedia,
                        childHidden,
                        effectiveNoMedia,
                    )
                continue
            }

            if (!child.isFile) continue
            if (!child.name.startsWith(".") && !effectiveNoMedia && !isDirectoryHidden) continue
            if (!child.isSupportedVideo()) continue

            val canonical = child.canonicalPath ?: child.absolutePath
            if (!seen.add(canonical)) continue

            // MediaStore doesn't index files hidden by name or by .nomedia, but if it somehow
            // already has this path, the row it returns is the source of truth — skip the duplicate.
            if (mediaStorePaths.contains(child.absolutePath) ||
                mediaStorePaths.contains(canonical)
            ) {
                continue
            }

            results += child
        }

        return results
    }

    /** True when any path segment of this directory starts with a dot. */
    private fun File.isHiddenPath(): Boolean =
        absolutePath.split(File.separator).any { it.startsWith(".") }

    /** True when this directory or any of its ancestors contains a `.nomedia` marker. */
    private fun File.hasNoMediaAncestor(): Boolean {
        var current: File? = this
        while (current != null) {
            if (File(
                    current,
                    ".nomedia",
                ).exists()
            ) {
                return true
            }
            val parent = current.parentFile ?: return false
            if (parent == current) return false
            current = parent
        }
        return false
    }

    private fun storageVolumeRoots(): List<String> = buildList {
        add(Environment.getExternalStorageDirectory().absolutePath)
        context.getExternalFilesDirs(null)
            .mapNotNull { it?.absolutePath?.substringBefore("/Android") }
            .filter { it.isNotBlank() }
            .forEach { add(it) }
    }.distinct()

    private fun mediaStoreVideoPaths(): Set<String> {
        val paths = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(index)?.let { paths.add(it) }
                }
            }
        }
        return paths
    }

    private fun isFileSystemAccessible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        // Android 10 honours requestLegacyExternalStorage, so direct File access still works.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching { isExternalStorageManager() }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isExternalStorageManager(): Boolean = Environment.isExternalStorageManager()

    private fun File.isSupportedVideo(): Boolean {
        val extension = name.substringAfterLast(
            '.',
            missingDelimiterValue = "",
        ).lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    companion object {
        private const val TAG = "HiddenVideoScanner"
        private const val MAX_CONCURRENT_METADATA_READS = 4
        private const val FIRST_SEEN_RECORD_HEADER = "#hidden-first-seen-v2"

        /**
         * How long a scan snapshot stays usable. Hidden folders produce no MediaStore change
         * notifications, so without this window files added after a scan would stay invisible
         * until a manual refresh.
         */
        private const val SCAN_FRESHNESS_MILLIS = 60_000L

        private val SUPPORTED_EXTENSIONS = setOf(
            "3gp",
            "3g2",
            "avi",
            "flv",
            "m2ts",
            "m4v",
            "mkv",
            "mov",
            "mp2",
            "mp4",
            "mpeg",
            "mpg",
            "mts",
            "ts",
            "webm",
            "wmv",
        )
    }
}
