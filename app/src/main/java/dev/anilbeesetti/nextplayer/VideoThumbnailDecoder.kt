package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Build.VERSION.SDK_INT
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
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.millisSince
import io.github.anilbeesetti.nextlib.mediainfo.MediaThumbnailRetriever
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.roundToInt
import okio.FileSystem

class VideoThumbnailDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val strategy: ThumbnailStrategy,
    private val diskCache: Lazy<DiskCache?>,
    private val mimeType: String?,
) : Decoder {

    private val diskCacheKey: String
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
        val snapshot = readFromDiskCache()
        if (snapshot == null) {
            logInfo("disk cache miss for $diskCacheKey")
        }
        snapshot?.use { snapshot ->
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

        // Cache miss OR cache insufficient: decode fresh thumbnail
        val startedAt = System.nanoTime()
        val nativeBitmap = if (strategy is ThumbnailStrategy.FirstFrame) {
            // The first frame never seeks, so it cannot stall on a container without an index.
            nativeFrame()
        } else {
            nativeFrameWithinBudget()
        }
        val rawBitmap = nativeBitmap ?: ffmpegFrame()
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

    /** Extracts the strategy's frame with the platform retriever. */
    private fun nativeFrame(): Bitmap? {
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

            val durationStart = System.nanoTime()
            val videoDuration = retriever.videoDuration()
            val durationMillis = millisSince(durationStart)

            val frame = frameWithFallback(
                videoDuration = videoDuration,
                getFrame = { timeUs ->
                    val seekStart = System.nanoTime()
                    retriever.getFrameAtTime(timeUs).also { bitmap ->
                        logFrame(
                            retriever = "native",
                            timeUs = timeUs,
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

    /**
     * Same as [nativeFrame], but bounded: seeking into a container without an index (e.g. a TS
     * recording) makes the retriever scan the file linearly, which can take many seconds for a
     * single thumbnail. The extraction runs on a shared daemon thread and, once the budget is
     * exhausted, the first frame - which never seeks - is extracted from a fresh retriever
     * instead. The abandoned seek keeps running on its own retriever and its bitmap is
     * discarded, so no retriever instance is ever used from two threads.
     */
    private fun nativeFrameWithinBudget(): Bitmap? {
        val future = deepSeekExecutor.submit<Bitmap?> { nativeFrame() }
        return try {
            future.get(
                DEEP_SEEK_BUDGET_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: TimeoutException) {
            logWarning(
                "deep seek exceeded the ${DEEP_SEEK_BUDGET_MILLIS}ms budget, " +
                    "falling back to the first frame",
            )
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(source = source)
                val seekStart = System.nanoTime()
                retriever.getFrameAtTime(0L).also { bitmap ->
                    logFrame(
                        retriever = "native",
                        timeUs = 0L,
                        bitmap = bitmap,
                        millis = millisSince(seekStart),
                    )
                }
            }
        } catch (exception: ExecutionException) {
            logError(
                message = "native frame extraction failed",
                throwable = exception.cause,
            )
            throw exception.cause ?: exception
        }
    }

    /**
     * Last resort for files the platform retriever cannot decode at all. FFmpeg re-opens and
     * re-probes the whole file (avformat_find_stream_info reads megabytes), so it is only
     * started once the native retriever fails to produce a bitmap.
     */
    private fun ffmpegFrame(): Bitmap? {
        val startedAt = System.nanoTime()
        val retriever = try {
            MediaThumbnailRetriever().apply { setDataSource(source) }
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
            val videoDuration = nativeVideoDuration()
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
                        "duration ${durationMillis}ms) ${frame.width}x${frame.height}",
                )
            }
            frame
        }
    }

    private fun logFrame(retriever: String, timeUs: Long, bitmap: Bitmap?, millis: Long) {
        val result = if (bitmap == null) "null" else "${bitmap.width}x${bitmap.height}"
        val message =
            "$retriever getFrameAtTime(${timeUs / 1000}ms) -> $result in ${millis}ms"
        if (bitmap == null) {
            logWarning(message)
        } else {
            logInfo(message)
        }
    }

    private fun logInfo(message: String) {
        Logger.logInfo(
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

    private fun nativeVideoDuration(): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(source = source)
        retriever.videoDuration()
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
                setDataSource(source.file().toFile().path)
            }

            else -> error(message = "Not supported")
        }
    }

    private fun MediaThumbnailRetriever.setDataSource(source: ImageSource) {
        val metadata = source.metadata
        when {
            metadata is ContentMetadata -> {
                // MediaStore can grant URI access to trashed videos while their paths
                // are inaccessible. Reuse the descriptor Coil already opened.
                setDataSource(metadata.assetFileDescriptor.parcelFileDescriptor)
            }

            source.fileSystem === FileSystem.SYSTEM -> {
                setDataSource(source.file().toFile().path)
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

        /**
         * Budget for a frame extraction that seeks. A seek into a container without an index
         * scans the file linearly, which is far too slow for a thumbnail; the first frame is
         * used instead once the budget is exhausted.
         */
        const val DEEP_SEEK_BUDGET_MILLIS = 2_500L

        val deepSeekExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(
                runnable,
                "video-thumbnail-seek",
            ).apply { isDaemon = true }
        }
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
