package com.nplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getStringForTime
import com.nplayer.feature.player.state.PlayerScrubState
import kotlin.math.roundToInt

private val PREVIEW_WIDTH = 160.dp
private val PREVIEW_GAP = 8.dp

/**
 * Frame under the finger while the seekbar is being dragged, drawn above the seekbar.
 *
 * Sits outside the controls layout so it stays visible while the bottom bar is animating,
 * and shows the time even when no frame could be decoded (network sources, undecodable files).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun ScrubPreviewOverlay(
    scrubState: PlayerScrubState,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (!scrubState.isScrubbing) return
    val bounds = scrubState.seekbarBounds
    if (bounds.isEmpty || durationMs <= 0L) return
    val frame = scrubState.previewFrame
    val fraction = (scrubState.scrubPositionMs.toFloat() / durationMs).coerceIn(
        0f,
        1f,
    )
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val previewWidthPx = with(density) { PREVIEW_WIDTH.toPx() }
    val gapPx = with(density) { PREVIEW_GAP.toPx() }
    val centerX = bounds.left + bounds.width * fraction
    val left = (centerX - previewWidthPx / 2f).coerceIn(
        minimumValue = 0f,
        maximumValue = (screenWidthPx - previewWidthPx).coerceAtLeast(minimumValue = 0f),
    )
    var overlayHeightPx by remember { mutableIntStateOf(value = 0) }
    Column(
        modifier = modifier
            .width(PREVIEW_WIDTH)
            .offset {
                IntOffset(
                    x = left.roundToInt(),
                    y = (bounds.top - gapPx - overlayHeightPx).coerceAtLeast(0f).roundToInt(),
                )
            }
            .onSizeChanged { overlayHeightPx = it.height }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.85f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frame.width.toFloat() / frame.height),
            )
        }
        Text(
            text = getStringForTime(scrubState.scrubPositionMs),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        )
    }
}
