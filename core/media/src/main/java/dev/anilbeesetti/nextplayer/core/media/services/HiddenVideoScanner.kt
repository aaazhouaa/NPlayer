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
import dev.anilbeesetti.nextplayer.core.common.millisSince
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
 * Duration and resolution are parsed in a background pass for files that no earlier scan has
 * parsed yet, so a slow (or very large) hidden library never delays the picker.
 * [observeCacheUpdates] emits once that pass completes so lists can re-query.
 *
 * Files added to a hidden folder never reach MediaStore, so no change notification can refresh
 * the caches. Scans are therefore re-walked once the cached snapshot is older than
 * [SCAN_FRESHNESS_MILLIS], and every file's first discovery time is recorded so newly added
 * files are reported with the same "added" semantics as MediaStore rows.
 *
 * Duration and resolution come from the persisted snapshots of earlier scans: those values only
 * change when the file itself changes, so they are reused for as long as the file's size and
 * modification time match. A scan therefore returns complete rows without opening any file,
 * including on the very first scan after a cold start.
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

    /**
     * Whether the persisted snapshots have been merged into [metadataCache] this process.
     *
     * Every snapshot under `filesDir` is a durable index of `path -> duration/width/height`.
     * Loading all of them once means a cold start reuses metadata parsed in earlier sessions
     * instead of re-opening every file, which is what keeps a folder's duration available
     * immediately rather than after a full background pass.
     */
    private var diskIndexLoaded = false

    /** Background metadata passes, one per scan key, so concurrent scans don't duplicate work. */
    private val enrichmentJobs = ConcurrentHashMap<ScanKey, Job>()

    private val cacheUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits when a background metadata pass parsed files that had no metadata yet, so the media
     * list can re-query and pick up durations that were still unknown when it was first emitted.
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
        if (!isFileSystemAccessible()) {
            // Android 11+ without MANAGE_EXTERNAL_STORAGE: the most common reason hidden media
            // never shows up, so it must not stay invisible in the logs.
            Logger.logWarning(
                tag = TAG,
                message = "hidden scan skipped: file system is not accessible " +
                    "(root=$root, sdk=${Build.VERSION.SDK_INT})",
            )
            return emptyList()
        }
        loadFirstSeen()
        seedMetadataIndex()

        val key = ScanKey(
            root,
            respectNoMedia,
        )
        val nowMillis = System.currentTimeMillis()
        cache[key]?.takeIf { it.isFresh(nowMillis = nowMillis) }?.let { cached ->
            Logger.logInfo(
                tag = TAG,
                message = "hidden scan served ${cached.videos.size} videos from memory cache " +
                    "(root=$root)",
            )
            return cached.videos
        }
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
            Logger.logInfo(
                tag = TAG,
                message = "hidden scan served ${cached.size} videos from disk cache (root=$root)",
            )
            return cached
        }

        val startedAt = System.nanoTime()
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

        // The list is emitted right away, already carrying every duration an earlier scan parsed
        // (in memory or restored from a persisted snapshot by seedMetadataIndex). Only files that
        // no scan has ever parsed wait for the background pass below.
        val videos = files.map { it.toMediaVideo(metadata = it.peekCachedVideoMetadata()) }
        cache[key] = CachedScan(
            videos = videos,
            scannedAtMillis = nowMillis,
        )
        // Persist immediately so the metadata survives a cancelled enrichment pass or a process
        // death; the pass refreshes it again once it has parsed the remaining files.
        saveToDisk(
            key = key,
            videos = videos,
        )
        persistFirstSeen(pruneMissingFiles = root == null)
        Logger.logInfo(
            tag = TAG,
            message = "hidden scan found ${videos.size} videos in ${files.size} files across " +
                "${roots.size} root(s) (${mediaStorePaths.size} already indexed, root=$root, " +
                "respectNoMedia=$respectNoMedia) in ${millisSince(startedAt)}ms",
        )
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
        }.onFailure { error ->
            Logger.logWarning(
                tag = TAG,
                message = "first-seen record write failed, discovery times are not persisted",
                throwable = error,
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
        // A container that cannot be parsed reports zeros; caching that would hide the real
        // values for the rest of the process, so only complete reads are remembered.
        if (metadata.duration <= 0L) return metadata
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
        if (duration <= 0L) return
        metadataCache[path] = CachedFileMetadata(
            size = size,
            lastModified = File(path).lastModified(),
            metadata = FileVideoMetadata(
                duration = duration,
                width = width,
                height = height,
            ),
        )
    }

    /**
     * Merges every persisted snapshot into [metadataCache] once per process.
     *
     * The snapshots are the durable form of what earlier scans parsed, and their key carries the
     * scan root - not the directory of the individual file - so a snapshot taken for the storage
     * root also serves scans of any folder beneath it. Entries stay validated by size and
     * modification time at lookup time, so a replaced file is parsed again.
     */
    private fun seedMetadataIndex() {
        if (diskIndexLoaded) return
        diskIndexLoaded = true
        val snapshots = context.filesDir.listFiles()
            ?.filter { it.name.startsWith(DISK_CACHE_PREFIX) }
            .orEmpty()
        val startedAt = System.nanoTime()
        var seeded = 0
        snapshots.forEach { snapshot ->
            runCatching { snapshot.readLines() }.getOrDefault(emptyList())
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && '\t' in line }
                .forEach { line ->
                    val parsed = line.toPersistedMetadata() ?: return@forEach
                    val added = metadataCache.putIfAbsent(
                        parsed.first,
                        parsed.second,
                    ) == null
                    if (added) seeded++
                }
        }
        Logger.logInfo(
            tag = TAG,
            message = "hidden metadata index seeded with $seeded entries from " +
                "${snapshots.size} snapshot(s) in ${millisSince(startedAt)}ms",
        )
    }

    /**
     * Parses a persisted `path\tduration\twidth\theight` line into its path and metadata.
     * Fields are read from the right so paths that themselves contain tabs survive.
     */
    private fun String.toPersistedMetadata(): Pair<String, CachedFileMetadata>? {
        var rest = this
        val height = rest.substringAfterLast('\t').toIntOrNull() ?: return null
        rest = rest.substringBeforeLast('\t')
        val width = rest.substringAfterLast('\t').toIntOrNull() ?: return null
        rest = rest.substringBeforeLast('\t')
        val duration = rest.substringAfterLast('\t').toLongOrNull() ?: return null
        val path = rest.substringBeforeLast('\t')
        if (duration <= 0L) return null
        val file = File(path).takeIf { it.isFile } ?: return null
        return path to CachedFileMetadata(
            size = file.length(),
            lastModified = file.lastModified(),
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
            "$DISK_CACHE_PREFIX${rootHash}_${key.respectNoMedia}.txt",
        )
    }

    private fun saveToDisk(key: ScanKey, videos: List<MediaVideo>) {
        if (videos.isEmpty()) return
        runCatching {
            diskFile(key).writeText(
                videos.joinToString(separator = "\n") { video ->
                    "${video.path}\t${video.duration}\t${video.width}\t${video.height}"
                },
            )
        }.onFailure { error ->
            Logger.logWarning(
                tag = TAG,
                message = "hidden scan snapshot write failed for ${diskFile(key).absolutePath} " +
                    "(${videos.size} entries)",
                throwable = error,
            )
        }
    }

    private fun loadFromDisk(key: ScanKey, nowMillis: Long): List<MediaVideo>? {
        val file = diskFile(key)
        if (!file.exists()) return null
        // A snapshot past the freshness window is re-walked, because files added to a hidden
        // folder never reach MediaStore and no change notification can refresh the cache. The
        // durations inside it are not wasted: seedMetadataIndex already merged them, and the
        // re-walk below reuses them for every file the snapshot described.
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
            ?.filter { it.name.startsWith(DISK_CACHE_PREFIX) }
            ?.forEach { it.delete() }
        // The index was built from exactly these snapshots.
        metadataCache.clear()
        diskIndexLoaded = false
    }

    /**
     * Parses duration and resolution for files that no scan has parsed yet.
     *
     * A file whose metadata is already known - parsed earlier in this process, or restored from
     * a persisted snapshot by [seedMetadataIndex] - is never opened again: its values can only
     * change when the file itself changes, which the size and modification time check catches.
     * A scan therefore only pays for files that no scan has ever parsed.
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
                    val pending = files.filter { file -> file.peekCachedVideoMetadata() == null }
                    if (pending.isNotEmpty()) {
                        pending.mapAsync { file ->
                            permits.withPermit { file.readCachedVideoMetadata() }
                        }
                    }
                    val videos = files.map { file ->
                        file.toMediaVideo(metadata = file.peekCachedVideoMetadata())
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
                Logger.logInfo(
                    tag = TAG,
                    message = "hidden metadata enrichment finished for ${enriched.size} files " +
                        "(root=${key.root})",
                )
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
        private const val DISK_CACHE_PREFIX = "hidden_scan_"
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
