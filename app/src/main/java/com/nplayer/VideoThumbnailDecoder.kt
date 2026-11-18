package com.nplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Build.VERSION.SDK_INT
import android.os.ParcelFileDescriptor
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import coil3.size.pxOrElse
import coil3.toAndroidUri
import coil3.util.component1
import coil3.util.component2
import com.nplayer.core.common.Logger
import com.nplayer.core.common.millisSince
import com.nplayer.core.thumbnail.FfmpegThumbnailRetriever
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem

class VideoThumbnailDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val strategy: ThumbnailStrategy,
    private val diskCache: Lazy<DiskCache?>,
    private val mimeType: String?,
) : Decoder {

    private val isTransportStream: Boolean by lazy {
        mimeType == "video/mp2t" || mimeType == "video/mp2ts" || hasTransportStreamPackets()
    }

    private fun hasTransportStreamPackets(): Boolean {
        val peek = source.source().peek()
        peek.request(TRANSPORT_STREAM_PROBE_BYTES)
        val header = peek.buffer
        for (packetSize in intArrayOf(
            188,
            192,
            204,
        )) {
            for (offset in 0 until packetSize) {
                if (offset + packetSize * 4L >= header.size) break
                if ((0 until 5).all { index ->
                        header[offset + index * packetSize.toLong()] == 0x47.toByte()
                    }
                ) {
                    logInfo(
                        "detected transport stream packets (size=$packetSize) for $sourceCacheKey",
                    )
                    return true
                }
            }
        }
        return false
    }

    private val diskCacheKey: String
        get() {
            val sourceKey = sourceCacheKey
            return if (isTransportStream) "ts-frame-v2:$strategy:$sourceKey" else sourceKey
        }

    private val sourceCacheKey: String
        get() = options.diskCacheKey ?: run {
            val metadata = source.metadata
            when {
                metadata is ContentMetadata -> metadata.uri.toAndroidUri().toString()
                source.fileSystem === FileSystem.SYSTEM -> source.file().toFile().path
                else -> error(message = "Not supported")
            }
        }

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun decode(): DecodeResult {
        logInfo(
            "decode start: $diskCacheKey " +
                "size=${options.size.width.pxOrElse { 0 }}x" +
                "${options.size.height.pxOrElse { 0 }}px " +
                "strategy=$strategy mime=$mimeType",
        )
        // Every image request builds its own decoder, so one pass over a grid can start several
        // decodes of the same video while the first one is still seeking. Letting them all open
        // the file competes for storage and turns a one second extraction into a minute; the
        // waiting requests instead reuse the entry the first one writes to the disk cache.
        val lock = inFlightLocks.computeIfAbsent(diskCacheKey) { Mutex() }
        try {
            return lock.withLock { decodeFromDiskCache() ?: decodeFreshFrame() }
        } finally {
            // A request arriving exactly now gets a fresh lock and decodes alongside this one.
            // Deduplication is an optimisation, so losing it for one frame is acceptable.
            inFlightLocks.remove(
                diskCacheKey,
                lock,
            )
        }
    }

    private fun decodeFromDiskCache(): DecodeResult? {
        val snapshot = readFromDiskCache()
        if (snapshot == null) {
            logInfo("disk cache miss for $diskCacheKey")
            return null
        }
        snapshot.use { snapshot ->
            val file = snapshot.data.toFile()

            // Read cached image dimensions (no pixel allocation)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    boundsOpts,
                )
            }
            val cachedWidth = boundsOpts.outWidth
            val cachedHeight = boundsOpts.outHeight

            // Determine what size the caller actually wants
            val requestedWidth = options.size.width.pxOrElse { 0 }
            val requestedHeight = options.size.height.pxOrElse { 0 }

            // Cache is sufficient only if requested size <= cached size (or size is unspecified)
            val cacheIsSufficient = (requestedWidth == 0 || requestedWidth <= cachedWidth) &&
                (requestedHeight == 0 || requestedHeight <= cachedHeight)

            if (cacheIsSufficient) {
                val dstSize = computeDstSize(
                    srcWidth = cachedWidth,
                    srcHeight = cachedHeight,
                )
                val sampledBitmap = file.inputStream().use { BitmapFactory.decodeStream(it) }
                // A truncated entry must not crash the decode: re-extract the frame instead.
                if (sampledBitmap != null) {
                    val normalizedBitmap = normalizeBitmap(
                        inBitmap = sampledBitmap,
                        srcWidth = cachedWidth,
                        srcHeight = cachedHeight,
                        dstSize = dstSize,
                    )
                    logInfo(
                        "disk cache hit for $diskCacheKey " +
                            "(${cachedWidth}x$cachedHeight -> " +
                            "${normalizedBitmap.width}x${normalizedBitmap.height})",
                    )
                    return DecodeResult(
                        image = normalizedBitmap.toDrawable(options.context.resources).asImage(),
                        isSampled = DecodeUtils.computeSizeMultiplier(
                            srcWidth = cachedWidth,
                            srcHeight = cachedHeight,
                            dstWidth = normalizedBitmap.width,
                            dstHeight = normalizedBitmap.height,
                            scale = options.scale,
                            maxSize = options.maxBitmapSize,
                        ) < 1.0,
                    )
                }
                logWarning("disk cache entry for $diskCacheKey is truncated, re-decoding")
            } else {
                logInfo(
                    "disk cache for $diskCacheKey is too small " +
                        "(${cachedWidth}x$cachedHeight < " +
                        "${requestedWidth}x$requestedHeight), re-decoding",
                )
            }
        }
        return null
    }

    private suspend fun decodeFreshFrame(): DecodeResult {
        // Cache miss OR cache insufficient: decode fresh thumbnail
        val startedAt = System.nanoTime()
        val rawBitmap = extractFrame()
        if (rawBitmap == null) {
            logError("no thumbnail for $diskCacheKey after ${millisSince(startedAt)}ms")
            throw IllegalStateException("Failed to get video thumbnail.")
        }
        logInfo(
            "decoded $diskCacheKey in ${millisSince(startedAt)}ms " +
                "(${rawBitmap.width}x${rawBitmap.height}, $strategy, $mimeType)",
        )

        val srcWidth = rawBitmap.width
        val srcHeight = rawBitmap.height
        val dstSize = computeDstSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
        )
        val scaledBitmap = normalizeBitmap(
            inBitmap = rawBitmap,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstSize = dstSize,
        )

        writeToDiskCache(inBitmap = scaledBitmap)

        return DecodeResult(
            image = scaledBitmap.toDrawable(options.context.resources).asImage(),
            isSampled = DecodeUtils.computeSizeMultiplier(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = scaledBitmap.width,
                dstHeight = scaledBitmap.height,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            ) < 1.0,
        )
    }

    /**
     * Extracts a frame according to [strategy] with [getFrame] alone.
     *
     * Duration metadata can report a length longer than the real stream, which makes a seek to
     * the requested position fail. Every path therefore retries with the first frame before
     * giving up, so a bad timestamp can never leave an item without a thumbnail.
     */
    private fun frameWithFallback(videoDuration: Long, getFrame: (Long) -> Bitmap?): Bitmap? =
        when (strategy) {
            is ThumbnailStrategy.FirstFrame ->
                getFrame(0L)

            is ThumbnailStrategy.FrameAtPercentage -> {
                val timeUs = videoDuration.percentageTimeUs(strategy.percentage)
                // A failed seek almost always means an unusable duration: retry the first frame
                // before FFmpeg repeats the same seek after a full re-open and re-probe.
                getFrame(timeUs) ?: getFrame(0L)
            }

            is ThumbnailStrategy.Hybrid -> {
                val firstFrame = getFrame(0L)
                if (firstFrame == null || isSolidColor(bitmap = firstFrame)) {
                    val timeUs = videoDuration.percentageTimeUs(strategy.percentage)
                    getFrame(timeUs) ?: firstFrame
                } else {
                    firstFrame
                }
            }
        }

    /**
     * Extracts the strategy's frame with the platform retriever.
     *
     * [openBudgetMillis] bounds how long opening the file may take. Opening a large MP4 costs
     * seconds on its own, so once that budget is spent the seek is skipped and the first frame -
     * which never seeks - is read from the retriever that is already open. That is the cheap way
     * out: dropping the retriever and opening the file again costs another full open.
     *
     * [firstFrameOnly] skips the seek regardless of [strategy]; see [extractFirstFrame].
     */
    private fun nativeFrame(
        openBudgetMillis: Long? = null,
        firstFrameOnly: Boolean = false,
    ): Bitmap? {
        val seekDeadlineNanos = openBudgetMillis?.let { budgetMillis ->
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        }
        val startedAt = System.nanoTime()
        return MediaMetadataRetriever().use { retriever ->
            val openStart = System.nanoTime()
            retriever.setDataSource(source = source)
            val openMillis = millisSince(openStart)

            val embeddedPicture = retriever.embeddedPicture?.decodeToBitmap()
            if (embeddedPicture != null) {
                logInfo(
                    "native embedded picture in ${millisSince(startedAt)}ms (open ${openMillis}ms)",
                )
                return@use embeddedPicture
            }

            if (firstFrameOnly) {
                return@use retriever.getFrameAtTime(0L).also { bitmap ->
                    logFrame(
                        retriever = "native",
                        timeUs = 0L,
                        bitmap = bitmap,
                        millis = millisSince(startedAt),
                    )
                }
            }

            val durationStart = System.nanoTime()
            val videoDuration = retriever.videoDuration()
            val durationMillis = millisSince(durationStart)

            val frame = frameWithFallback(
                videoDuration = videoDuration,
                getFrame = { timeUs ->
                    val seekTimeUs = if (
                        timeUs != 0L && seekDeadlineNanos != null &&
                        System.nanoTime() - seekDeadlineNanos > 0
                    ) {
                        logWarning(
                            "opening the file used the whole budget, reading the first frame " +
                                "instead of seeking to ${timeUs / 1000}ms",
                        )
                        0L
                    } else {
                        timeUs
                    }
                    val seekStart = System.nanoTime()
                    retriever.getFrameAtTime(seekTimeUs).also { bitmap ->
                        logFrame(
                            retriever = "native",
                            timeUs = seekTimeUs,
                            bitmap = bitmap,
                            millis = millisSince(seekStart),
                        )
                    }
                },
            )
            val totalMillis = millisSince(startedAt)
            if (frame == null) {
                logWarning(
                    "native retriever produced no frame in ${totalMillis}ms " +
                        "(open ${openMillis}ms, duration ${durationMillis}ms), trying ffmpeg",
                )
            } else {
                logInfo(
                    "native frame in ${totalMillis}ms (open ${openMillis}ms, " +
                        "duration ${durationMillis}ms, reported duration ${videoDuration}ms) " +
                        "${frame.width}x${frame.height}",
                )
            }
            frame
        }
    }

    /** The strategy's frame, then the cheapest fallbacks that can still produce a picture. */
    private suspend fun extractFrame(): Bitmap? {
        if (isTransportStream) {
            logInfo("using ffmpeg for transport stream $sourceCacheKey at $strategy")
            return extractFfmpegFrame() ?: extractNativeFrame().bitmapOrNull()
        }
        return extractPlatformFrame()
    }

    private suspend fun extractPlatformFrame(): Bitmap? = when (val native = extractNativeFrame()) {
        // The retriever finished but produced nothing, so the file needs FFmpeg. This is rare
        // and bounded, because the extraction above completed within its budget.
        is Extraction.Finished -> native.bitmap ?: extractFfmpegFrame()

        // The extraction was abandoned after taking far too long. For a first-frame strategy
        // that was the only picture it knows how to read, so FFmpeg is the last option. For a
        // seeking strategy FFmpeg would repeat the very scan that was just given up on, so the
        // first frame - one open, no seek - is tried first.
        Extraction.Abandoned ->
            if (strategy is ThumbnailStrategy.FirstFrame) {
                extractFfmpegFrame()
            } else {
                extractFirstFrame() ?: extractFfmpegFrame()
            }
    }

    /**
     * Extracts the strategy's frame on the shared pool.
     *
     * A seek into a container without an index (e.g. a TS recording) makes the retriever scan the
     * file linearly, which can take a minute for a single thumbnail and cannot be interrupted.
     * Such an attempt is abandoned once its budget is spent and [extractFrame] picks a fallback.
     * The abandoned attempt keeps its slot until the scan ends, so [openSlots] is what stops
     * those scans from piling up.
     */
    private suspend fun extractNativeFrame(): Extraction<Bitmap?> {
        val firstFrameOnly = strategy is ThumbnailStrategy.FirstFrame
        return awaitPooled(
            guardMillis = if (firstFrameOnly) FIRST_FRAME_BUDGET_MILLIS else SEEK_BUDGET_MILLIS,
            abandonedMessage = "native extraction for $diskCacheKey did not finish in time, " +
                "abandoning it",
        ) {
            // Started inside the task so that time spent queued behind other videos does not
            // eat into the budget of an open that has not begun yet. The first frame never
            // seeks, so there is nothing to bound.
            nativeFrame(
                openBudgetMillis = if (firstFrameOnly) null else OPEN_BUDGET_MILLIS,
            )
        }
    }

    private suspend fun extractFfmpegFrame(): Bitmap? = awaitPooled(
        guardMillis = FFMPEG_BUDGET_MILLIS,
        abandonedMessage = "ffmpeg extraction for $diskCacheKey did not finish in " +
            "${FFMPEG_BUDGET_MILLIS}ms, abandoning it",
        slots = if (isTransportStream) transportStreamSlots else openSlots,
        block = ::ffmpegFrame,
    ).bitmapOrNull()

    /** Reads the first frame, which never seeks, as the last resort after a long seek. */
    private suspend fun extractFirstFrame(): Bitmap? = awaitPooled(
        guardMillis = FIRST_FRAME_BUDGET_MILLIS,
        abandonedMessage = "first frame for $diskCacheKey did not arrive in " +
            "${FIRST_FRAME_BUDGET_MILLIS}ms, abandoning it",
    ) {
        nativeFrame(
            openBudgetMillis = null,
            firstFrameOnly = true,
        )
    }.bitmapOrNull()

    private fun Extraction<Bitmap?>.bitmapOrNull(): Bitmap? = when (this) {
        is Extraction.Finished -> bitmap
        Extraction.Abandoned -> null
    }

    /**
     * Runs [block] with a slot held in [openSlots] and waits for it without holding a thread.
     *
     * Every open of a video file runs inside a slot, so the number of slots is the ceiling on how
     * many files are open at once. Waiting for a slot is normal backpressure rather than a
     * failure and is therefore untimed relative to the file: [guardMillis] only starts once
     * [block] is running, so a grid that queues several videos cannot be mistaken for a wedged
     * extraction. The caller suspends instead of blocking, which keeps Coil's own threads free.
     *
     * The slot wait itself is still bounded by [SLOT_WAIT_MILLIS], because a retriever that never
     * returns would otherwise hold its slot forever and leave every later request suspended
     * indefinitely. Running out of slots is reported as [Extraction.Abandoned] so the request
     * fails promptly and can be retried, instead of pinning a Coil coroutine.
     */
    private suspend fun awaitPooled(
        guardMillis: Long,
        abandonedMessage: String,
        slots: Semaphore = openSlots,
        block: () -> Bitmap?,
    ): Extraction<Bitmap?> {
        val acquired = withTimeoutOrNull(SLOT_WAIT_MILLIS) { slots.acquire() }
        if (acquired == null) {
            logWarning("no free extraction slot for $diskCacheKey, abandoning it")
            return Extraction.Abandoned
        }
        val result = CompletableDeferred<Result<Bitmap?>>()
        try {
            extractionExecutor.execute {
                try {
                    result.complete(runCatching(block))
                } finally {
                    // Released by the task, not by the caller: an abandoned extraction keeps
                    // scanning until the retriever returns, and that scan must keep counting
                    // against the limit or abandoned work would pile up and make every other
                    // file slower.
                    slots.release()
                }
            }
        } catch (error: RejectedExecutionException) {
            slots.release()
            throw error
        }
        val outcome = withTimeoutOrNull(guardMillis) { result.await() }
        if (outcome == null) {
            logWarning(abandonedMessage)
            return Extraction.Abandoned
        }
        return Extraction.Finished(
            outcome.getOrElse { error ->
                if (error is CancellationException || error !is Exception) throw error
                logError(
                    message = "thumbnail extraction failed for $diskCacheKey",
                    throwable = error,
                )
                null
            },
        )
    }

    private fun ffmpegFrame(): Bitmap? {
        val startedAt = System.nanoTime()
        val retriever = try {
            FfmpegThumbnailRetriever().apply { setDataSource(source) }
        } catch (exception: IllegalArgumentException) {
            // FFmpeg can fail to reopen a trashed MediaStore descriptor even
            // when the native retriever can read it.
            logWarning("ffmpeg open failed in ${millisSince(startedAt)}ms: ${exception.message}")
            return null
        }
        val openMillis = millisSince(startedAt)
        return retriever.use { ffmpeg ->
            val embeddedPicture = ffmpeg.getEmbeddedPicture()?.decodeToBitmap()
            if (embeddedPicture != null) {
                logInfo(
                    "ffmpeg embedded picture in ${millisSince(startedAt)}ms (open ${openMillis}ms)",
                )
                return@use embeddedPicture
            }

            val durationStart = System.nanoTime()
            val videoDuration = ffmpeg.durationMillis
            val durationMillis = millisSince(durationStart)

            val frame = frameWithFallback(
                videoDuration = videoDuration,
                getFrame = { timeUs ->
                    val seekStart = System.nanoTime()
                    ffmpeg.getFrameAtTime(timeUs).also { bitmap ->
                        logFrame(
                            retriever = "ffmpeg",
                            timeUs = timeUs,
                            bitmap = bitmap,
                            millis = millisSince(seekStart),
                        )
                    }
                },
            )
            val totalMillis = millisSince(startedAt)
            if (frame == null) {
                logError(
                    "ffmpeg produced no frame in ${totalMillis}ms " +
                        "(open ${openMillis}ms, duration ${durationMillis}ms)",
                )
            } else {
                logInfo(
                    "ffmpeg frame in ${totalMillis}ms (open ${openMillis}ms, " +
                        "duration ${durationMillis}ms, reported duration ${videoDuration}ms) " +
                        "${frame.width}x${frame.height}",
                )
            }
            frame
        }
    }

    private fun logFrame(retriever: String, timeUs: Long, bitmap: Bitmap?, millis: Long) {
        val result = if (bitmap == null) "null" else "${bitmap.width}x${bitmap.height}"
        val message =
            "$retriever getFrameAtTime(${timeUs / 1000}ms) -> $result in ${millis}ms " +
                "for $sourceCacheKey"
        if (bitmap == null) {
            logWarning(message)
        } else {
            logInfo(message)
        }
    }

    private fun logInfo(message: String) {
        // Per-frame detail: only recorded when verbose logging is enabled.
        Logger.logDebug(
            tag = LOG_TAG,
            message = message,
        )
    }

    private fun logWarning(message: String) {
        Logger.logWarning(
            tag = LOG_TAG,
            message = message,
        )
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        Logger.logError(
            tag = LOG_TAG,
            message = message,
            throwable = throwable,
        )
    }

    private fun MediaMetadataRetriever.videoDuration(): Long =
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    private fun Long.percentageTimeUs(percentage: Float): Long = (this * percentage * 1000).toLong()

    private fun ByteArray.decodeToBitmap(): Bitmap? = BitmapFactory.decodeByteArray(
        this,
        0,
        size,
    )

    private fun MediaMetadataRetriever.setDataSource(source: ImageSource) {
        val metadata = source.metadata
        when {
            metadata is ContentMetadata -> {
                setDataSource(
                    options.context,
                    metadata.uri.toAndroidUri(),
                )
            }

            source.fileSystem === FileSystem.SYSTEM -> {
                ParcelFileDescriptor.open(
                    source.file().toFile(),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { descriptor ->
                    setDataSource(
                        descriptor.fileDescriptor,
                        0L,
                        descriptor.statSize,
                    )
                }
            }

            else -> error(message = "Not supported")
        }
    }

    private fun FfmpegThumbnailRetriever.setDataSource(source: ImageSource) {
        val metadata = source.metadata
        when {
            metadata is ContentMetadata -> {
                // A cancelled Coil request may close its descriptor before this worker starts.
                options.context.contentResolver.openFileDescriptor(
                    metadata.uri.toAndroidUri(),
                    "r",
                )?.use { descriptor ->
                    setDataSource(descriptor)
                } ?: error("Unable to open video descriptor.")
            }

            source.fileSystem === FileSystem.SYSTEM -> {
                ParcelFileDescriptor.open(
                    source.file().toFile(),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { descriptor ->
                    setDataSource(descriptor)
                }
            }

            else -> error(message = "Not supported")
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? =
        if (options.diskCachePolicy.readEnabled) {
            diskCache.value?.openSnapshot(key = diskCacheKey)
        } else {
            null
        }

    private fun writeToDiskCache(inBitmap: Bitmap) {
        if (!options.diskCachePolicy.writeEnabled) return
        val editor = diskCache.value?.openEditor(key = diskCacheKey) ?: return
        try {
            editor.data.toFile().outputStream().use { output ->
                inBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    output,
                )
            }
            editor.commit()
        } catch (_: Exception) {
            runCatching { editor.abort() }
        }
    }

    private fun normalizeBitmap(
        inBitmap: Bitmap,
        srcWidth: Int,
        srcHeight: Int,
        dstSize: Size,
    ): Bitmap {
        val scale = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstSize.width.pxOrElse { inBitmap.width },
            dstHeight = dstSize.height.pxOrElse { inBitmap.height },
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        ).toFloat()

        if (scale == 1f) return inBitmap

        val dstWidth = (scale * inBitmap.width).roundToInt()
        val dstHeight = (scale * inBitmap.height).roundToInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val outBitmap = createBitmap(
            width = dstWidth,
            height = dstHeight,
            config =
                inBitmap.config ?: Bitmap.Config.ARGB_8888,
        )
        outBitmap.applyCanvas {
            scale(
                scale,
                scale,
            )
            drawBitmap(
                inBitmap,
                0f,
                0f,
                paint,
            )
        }
        inBitmap.recycle()
        return outBitmap
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun computeDstSize(srcWidth: Int, srcHeight: Int): Size {
        if (srcWidth <= 0 || srcHeight <= 0) return Size.ORIGINAL

        val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            targetSize = options.size,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        val rawScale = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        val finalScale = if (options.precision == Precision.INEXACT) {
            rawScale.coerceAtMost(maximumValue = 1.0)
        } else {
            rawScale
        }
        return Size(
            (finalScale * srcWidth).roundToInt(),
            (finalScale * srcHeight).roundToInt(),
        )
    }

    /**
     * Result of a bounded extraction: either the work finished (possibly with no bitmap) or it
     * was given up on, which the caller has to handle differently.
     */
    private sealed interface Extraction<out T> {
        data class Finished<T>(val bitmap: T) : Extraction<T>
        data object Abandoned : Extraction<Nothing>
    }

    class Factory(private val thumbnailStrategy: () -> ThumbnailStrategy) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isApplicable(result.mimeType)) return null
            return VideoThumbnailDecoder(
                source = result.source,
                options = options,
                strategy = thumbnailStrategy(),
                diskCache = lazy { imageLoader.diskCache },
                mimeType = result.mimeType,
            )
        }

        private fun isApplicable(mimeType: String?): Boolean =
            mimeType != null && mimeType.startsWith(prefix = "video/")
    }

    private companion object {
        const val LOG_TAG = "Thumbnail"
        const val TRANSPORT_STREAM_PROBE_BYTES = 1_024L

        /**
         * How long opening the file may take before the seek is skipped. Opening a
         * multi-gigabyte MP4 takes seconds by itself, while a seek into a file it has opened
         * costs well under one; the seek is what is worth abandoning, not the open.
         */
        const val OPEN_BUDGET_MILLIS = 4_000L

        /**
         * How long a native extraction may take in total. Generous enough that a healthy file
         * never reaches it even when requests queue for a pool slot, so it only fires on the seek
         * that scans a container without an index.
         */
        const val SEEK_BUDGET_MILLIS = 15_000L

        /** Same idea for the FFmpeg fallback, which re-probes the whole file. */
        const val FFMPEG_BUDGET_MILLIS = 20_000L

        /** The first frame needs one open and no seek, so it only needs a short guard. */
        const val FIRST_FRAME_BUDGET_MILLIS = 10_000L

        /**
         * How long a request may wait for a free slot. Long enough that a full grid simply
         * queues, short enough that leaked slots cannot suspend requests forever.
         */
        const val SLOT_WAIT_MILLIS = 60_000L

        /** Number of extractions allowed to open a file at the same time. */
        const val CONCURRENT_OPEN_LIMIT = 6

        /**
         * Shared by every decoder: each image request builds its own decoder, so both the
         * concurrency limit and the per-key locks only work when they live here.
         */
        val openSlots = Semaphore(permits = CONCURRENT_OPEN_LIMIT)

        // Platform seeks cannot be interrupted and must not block transport-stream extraction.
        val transportStreamSlots = Semaphore(permits = 2)

        /**
         * Never queues behind more than [CONCURRENT_OPEN_LIMIT] running extractions, because
         * [openSlots] is acquired before a task is submitted.
         */
        val extractionExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(
                runnable,
                "video-thumbnail-open",
            ).apply { isDaemon = true }
        }

        /** One lock per key being decoded right now, discarded as soon as the decode returns. */
        val inFlightLocks = ConcurrentHashMap<String, Mutex>()
    }
}

private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        // We must call 'close' on API 29+ to avoid a strict mode warning.
        if (SDK_INT >= 29) {
            close()
        } else {
            release()
        }
    }
}

/**
 * Sealed class representing different thumbnail generation strategies
 */
sealed class ThumbnailStrategy {
    data object FirstFrame : ThumbnailStrategy()
    data class FrameAtPercentage(val percentage: Float = 0.33f) : ThumbnailStrategy()
    data class Hybrid(val percentage: Float = 0.33f) : ThumbnailStrategy()
}

/**
 * Checks if a bitmap is mostly a solid color.
 * Uses a scaling approach to sample the center region and compares pixels to the center color.
 * Returns true if [threshold] (default 95%) or more of sampled pixels are similar.
 */
private fun isSolidColor(bitmap: Bitmap, threshold: Float = 0.7f): Boolean {
    val width = bitmap.width
    val height = bitmap.height

    // Sample a grid in the center region (avoiding edges which may be black bars)
    val marginX = width / 10
    val marginY = height / 10
    val sampleAreaRight = width - marginX
    val sampleAreaBottom = height - marginY

    // Create a grid of sample points
    val gridSize = 10
    val stepX = (sampleAreaRight - marginX) / gridSize
    val stepY = (sampleAreaBottom - marginY) / gridSize

    if (stepX <= 0 || stepY <= 0) return false

    val sampledColors = mutableListOf<Int>()

    for (x in 0 until gridSize) {
        for (y in 0 until gridSize) {
            val pixelX = marginX + x * stepX
            val pixelY = marginY + y * stepY
            if (pixelX < width && pixelY < height) {
                sampledColors.add(element = bitmap[pixelX, pixelY])
            }
        }
    }

    if (sampledColors.isEmpty()) return false

    // Use the first color as reference
    val referenceColor = sampledColors[0]
    val referenceR = (referenceColor shr 16) and 0xFF
    val referenceG = (referenceColor shr 8) and 0xFF
    val referenceB = referenceColor and 0xFF

    // Count similar colors (within a tolerance)
    val tolerance = 30 // RGB tolerance
    val similarCount = sampledColors.count { color ->
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        abs(n = r - referenceR) <= tolerance &&
            abs(n = g - referenceG) <= tolerance &&
            abs(n = b - referenceB) <= tolerance
    }

    val similarityRatio = similarCount.toFloat() / sampledColors.size
    return similarityRatio >= threshold
}
