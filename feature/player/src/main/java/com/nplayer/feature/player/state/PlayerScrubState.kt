package com.nplayer.feature.player.state

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** How long the finger has to rest before the frame under it is decoded. */
private const val PREVIEW_SETTLE_DELAY_MS = 120L

private const val PREVIEW_WIDTH_PX = 320

private val LOCAL_SCHEMES = setOf(
    "content",
    "file",
    "android.resource",
)

@OptIn(UnstableApi::class)
@Composable
fun rememberPlayerScrubState(player: Player): PlayerScrubState {
    val context = LocalContext.current
    val state = remember(player) { PlayerScrubState(player = player) }
    val loader = remember(context) { ScrubPreviewLoader(context = context) }
    DisposableEffect(loader) {
        onDispose { loader.release() }
    }
    LaunchedEffect(
        state,
        loader,
    ) {
        snapshotFlow { state.previewRequest }
            .distinctUntilChanged()
            .collectLatest { request ->
                if (request == null) {
                    state.setPreview(bitmap = null)
                    return@collectLatest
                }
                // Resting the finger is what asks for a frame; continuous movement keeps
                // restarting this delay, so a drag never queues a decode per pixel.
                delay(PREVIEW_SETTLE_DELAY_MS)
                state.setPreview(
                    bitmap = loader.frame(
                        uri = request.uri,
                        timeMs = request.timeMs,
                    ),
                )
            }
    }
    return state
}

/**
 * Position chosen by the seekbar drag before it is committed to the player.
 *
 * The player keeps playing while the finger moves, so the slider follows [scrubPositionMs]
 * instead of the live position and only jumps on [endScrub].
 */
@OptIn(UnstableApi::class)
@Stable
class PlayerScrubState(private val player: Player) {
    var isScrubbing: Boolean by mutableStateOf(value = false)
        private set

    var isCancelled: Boolean by mutableStateOf(value = false)
        private set

    var scrubPositionMs: Long by mutableStateOf(value = 0L)
        private set

    var seekbarBounds: Rect by mutableStateOf(value = Rect.Zero)
        private set

    var previewFrame: Bitmap? by mutableStateOf(value = null)
        private set

    internal var previewRequest: PreviewRequest? by mutableStateOf(value = null)
        private set

    private var gestureActive = false
    private var previewSource: PreviewSource? = null

    fun onScrub(positionMs: Long) {
        if (isCancelled) return
        if (!gestureActive) {
            gestureActive = true
            previewSource = queryPreviewSource()
        }
        isScrubbing = true
        scrubPositionMs = positionMs
        previewRequest = previewSource?.let {
            PreviewRequest(
                uri = it.uri,
                timeMs = positionMs,
            )
        }
    }

    fun cancel() {
        if (isCancelled) return
        isCancelled = true
        isScrubbing = false
        previewRequest = null
        previewFrame = null
    }

    fun resume() {
        if (!isCancelled) return
        isCancelled = false
        isScrubbing = true
        scrubPositionMs = player.currentPosition.coerceAtLeast(minimumValue = 0L)
        previewRequest = previewSource?.let {
            PreviewRequest(
                uri = it.uri,
                timeMs = scrubPositionMs,
            )
        }
    }

    /** @return the position to seek to, or null when the gesture was cancelled. */
    fun endScrub(): Long? {
        val committed = scrubPositionMs.takeIf { gestureActive && !isCancelled }
        gestureActive = false
        isScrubbing = false
        isCancelled = false
        previewRequest = null
        previewSource = null
        previewFrame = null
        return committed
    }

    fun updateSeekbarBounds(bounds: Rect) {
        seekbarBounds = bounds
    }

    internal fun setPreview(bitmap: Bitmap?) {
        previewFrame = bitmap
    }

    private fun queryPreviewSource(): PreviewSource? = runCatching {
        if (!player.isCurrentMediaItemSeekable) return@runCatching null
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return@runCatching null
        if (uri.scheme?.lowercase() !in LOCAL_SCHEMES) return@runCatching null
        PreviewSource(uri = uri)
    }.getOrNull()

    internal data class PreviewRequest(val uri: Uri, val timeMs: Long)

    private data class PreviewSource(val uri: Uri)
}

/**
 * Decodes frames on a single background thread and keeps one retriever per source, since
 * opening a demuxer costs far more than seeking within an already opened one.
 */
private class ScrubPreviewLoader(private val context: Context) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(parallelism = 1)
    private var retriever: MediaMetadataRetriever? = null
    private var sourceUri: Uri? = null
    private var heightPx = PREVIEW_WIDTH_PX * 9 / 16

    suspend fun frame(uri: Uri, timeMs: Long): Bitmap? = withContext(dispatcher) {
        val retriever = retriever(uri = uri) ?: return@withContext null
        runCatching {
            retriever.getScaledFrameAtTime(
                timeMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                PREVIEW_WIDTH_PX,
                heightPx,
            )
        }.getOrNull()
    }

    fun release() {
        retriever?.releaseSafely()
        retriever = null
        sourceUri = null
    }

    private fun retriever(uri: Uri): MediaMetadataRetriever? {
        retriever?.let { if (sourceUri == uri) return it }
        release()
        val created = MediaMetadataRetriever()
        return runCatching {
            created.setDataSource(
                context,
                uri,
            )
            heightPx = previewHeightPx(retriever = created)
            retriever = created
            sourceUri = uri
            created
        }.getOrElse {
            created.releaseSafely()
            null
        }
    }

    private fun previewHeightPx(retriever: MediaMetadataRetriever): Int {
        val width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
        )?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
        )?.toIntOrNull() ?: 0
        val (frameWidth, frameHeight) = if (rotation % 180 == 0) {
            width to height
        } else {
            height to width
        }
        if (frameWidth <= 0 || frameHeight <= 0) return PREVIEW_WIDTH_PX * 9 / 16
        return (PREVIEW_WIDTH_PX.toLong() * frameHeight / frameWidth).toInt()
            .coerceAtLeast(minimumValue = 1)
    }
}

private fun MediaMetadataRetriever.releaseSafely() {
    runCatching { release() }
}
