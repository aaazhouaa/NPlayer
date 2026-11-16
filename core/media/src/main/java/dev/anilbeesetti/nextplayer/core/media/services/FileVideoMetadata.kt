package dev.anilbeesetti.nextplayer.core.media.services

import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlinx.coroutines.CancellationException

/** Duration and resolution parsed straight from a video file's container. */
internal data class FileVideoMetadata(val duration: Long, val width: Int, val height: Int)

/**
 * Reads duration and resolution from this file, or null when the container cannot be parsed.
 *
 * MediaStore has no row for files it never indexes (dot-prefixed names, dot directories,
 * directories with a `.nomedia` marker), so its DURATION/WIDTH/HEIGHT columns are
 * unavailable and the values have to be parsed from the container itself.
 */
internal fun File.readVideoMetadata(): FileVideoMetadata? = try {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(absolutePath)
        FileVideoMetadata(
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0,
            height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0,
        )
    } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            retriever.close()
        } else {
            @Suppress("DEPRECATION")
            retriever.release()
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // A single unreadable or corrupt file must never fail the whole scan: the retriever
    // reports failures as RuntimeException, and close() can raise IOException.
    null
}
