package dev.anilbeesetti.nextplayer.core.thumbnail

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import java.io.Closeable

class FfmpegThumbnailRetriever : Closeable {
    private var nativeHandle = 0L

    fun setDataSource(filePath: String) {
        close()
        nativeHandle = nativeCreateFromPath(filePath)
        require(nativeHandle != 0L) { "Unable to open media source from path." }
    }

    fun setDataSource(descriptor: ParcelFileDescriptor) {
        close()
        nativeHandle = nativeCreateFromFD(descriptor.fd)
        require(nativeHandle != 0L) { "Unable to open media source from file descriptor." }
    }

    val durationMillis: Long
        get() = nativeGetDurationMillis(requireHandle())

    fun getEmbeddedPicture(): ByteArray? = nativeGetEmbeddedPicture(requireHandle())

    fun getFrameAtTime(timeUs: Long): Bitmap? {
        require(timeUs >= 0) { "timeUs must be >= 0" }
        val handle = requireHandle()
        val bitmap = nativeGetFrameAtTime(
            handle,
            timeUs,
        ) ?: return null
        val degrees = nativeGetRotationDegrees(handle)
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun requireHandle(): Long {
        check(nativeHandle != 0L) { "Data source is not set." }
        return nativeHandle
    }

    private companion object {
        init {
            System.loadLibrary("nextplayer_thumbnail")
        }

        @JvmStatic
        external fun nativeCreateFromPath(filePath: String): Long

        @JvmStatic
        external fun nativeCreateFromFD(fileDescriptor: Int): Long

        @JvmStatic
        external fun nativeGetDurationMillis(handle: Long): Long

        @JvmStatic
        external fun nativeGetEmbeddedPicture(handle: Long): ByteArray?

        @JvmStatic
        external fun nativeGetFrameAtTime(handle: Long, timeUs: Long): Bitmap?

        @JvmStatic
        external fun nativeGetRotationDegrees(handle: Long): Int

        @JvmStatic
        external fun nativeRelease(handle: Long)
    }
}
