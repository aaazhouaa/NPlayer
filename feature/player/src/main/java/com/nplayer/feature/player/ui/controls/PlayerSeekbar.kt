package com.nplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import com.nplayer.feature.player.LocalUseMaterialYouControls
import com.nplayer.feature.player.model.AbRepeat
import com.nplayer.feature.player.state.PlayerScrubState
import com.nplayer.feature.player.state.currentChapterIndex
import kotlin.math.abs

/**
 * Height of the seekbar's touch target, which is taller than the 4-8dp track it draws.
 *
 * Material's 48dp minimum only bounds the component, it is not a comfortable size: a fingertip
 * covers 8-10mm, so a 48dp (7.6mm) strip still gets missed above and below. 64dp (10.2mm)
 * covers the fingertip instead, at the cost of a taller bottom bar.
 */
private val SEEKBAR_TOUCH_TARGET = 64.dp

/**
 * Height Material's `Slider` accepts presses in, regardless of how tall a container it is given.
 * Its internal minimum interactive size wins over the caller's height, leaving the rest of
 * [SEEKBAR_TOUCH_TARGET] inert.
 */
private val SLIDER_OWN_TOUCH_HEIGHT = 48.dp

/** Half the height of the tallest track drawn, used to locate the track's top edge. */
private val TRACK_HALF_HEIGHT = 4.dp

/** How far above the track a drag has to move before it stops seeking. */
private val SCRUB_CANCEL_DISTANCE = 20.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    UnstableApi::class,
)
@Composable
internal fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    scrubState: PlayerScrubState,
    position: Float,
    duration: Float,
    chapters: List<Chapter>,
    abRepeat: AbRepeat? = null,
    onSeekCommit: (Long) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val currentOnSeekCommit by rememberUpdatedState(newValue = onSeekCommit)
    var lastSeekChapterIndex by remember(chapters) { mutableStateOf<Int?>(value = null) }
    val onValueChange: (Float) -> Unit = { value ->
        if (!scrubState.isCancelled) {
            scrubState.onScrub(positionMs = value.toLong())
            val chapterIndex = chapters.currentChapterIndex(value.toLong())
            val previousChapterIndex =
                lastSeekChapterIndex ?: chapters.currentChapterIndex(position.toLong())
            if (chapterIndex != previousChapterIndex) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            lastSeekChapterIndex = chapterIndex
        }
    }
    val onValueChangeFinished: () -> Unit = {
        lastSeekChapterIndex = null
        scrubState.endScrub()?.let { currentOnSeekCommit(it) }
    }
    val useMaterialYouControls = LocalUseMaterialYouControls.current
    DisposableEffect(key1 = useMaterialYouControls) {
        // Controls can disappear or change style before a pointer/key release reaches the slider.
        onDispose { onValueChangeFinished() }
    }
    var isFocused by remember { mutableStateOf(value = false) }
    val focusModifier = modifier
        .fillMaxWidth()
        .onFocusChanged {
            if (isFocused && !it.isFocused) onValueChangeFinished()
            isFocused = it.isFocused
        }
    val density = LocalDensity.current
    val sliderOwnHalfHeightPx = with(density) { SLIDER_OWN_TOUCH_HEIGHT.toPx() / 2f }
    val trackHalfHeightPx = with(density) { TRACK_HALF_HEIGHT.toPx() }
    val cancelDistancePx = with(density) { SCRUB_CANCEL_DISTANCE.toPx() }
    val currentDuration by rememberUpdatedState(newValue = duration)
    val currentOnValueChange by rememberUpdatedState(newValue = onValueChange)
    val displayedPosition = if (scrubState.isScrubbing) {
        scrubState.scrubPositionMs.toFloat()
    } else {
        position
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEEKBAR_TOUCH_TARGET)
            .onGloballyPositioned { coordinates ->
                scrubState.updateSeekbarBounds(coordinates.boundsInRoot())
            }
            .pointerInput(scrubState) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (currentDuration <= 0f) return@awaitEachGesture
                    // Presses in the padding around the slider are claimed here, since the
                    // slider would otherwise let them reach the tap handler behind. Presses on
                    // the slider itself stay with the slider, which drives onValueChange.
                    val startsOnSlider =
                        abs(down.position.y - size.height / 2f) <= sliderOwnHalfHeightPx
                    if (!startsOnSlider) {
                        down.consume()
                        val fraction = (down.position.x / size.width).coerceIn(
                            minimumValue = 0f,
                            maximumValue = 1f,
                        )
                        currentOnValueChange(fraction * currentDuration)
                    }
                    // Cancel line is 20dp above the track, but never closer to the finger than
                    // the press itself: a press that already lands in that band has still not
                    // moved upwards.
                    val trackTopY = size.height / 2f - trackHalfHeightPx
                    val cancelLineY = minOf(
                        trackTopY - cancelDistancePx,
                        down.position.y - cancelDistancePx,
                    )
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!startsOnSlider) {
                            change.consume()
                            if (!cancelled) {
                                val fraction = (change.position.x / size.width).coerceIn(
                                    minimumValue = 0f,
                                    maximumValue = 1f,
                                )
                                currentOnValueChange(fraction * currentDuration)
                            }
                        }
                        val aboveCancelLine = change.position.y < cancelLineY
                        if (aboveCancelLine != cancelled) {
                            cancelled = aboveCancelLine
                            if (cancelled) {
                                scrubState.cancel()
                            } else {
                                // The position jumped back to the playing position, so the next
                                // chapter has to be measured from there.
                                lastSeekChapterIndex = null
                                scrubState.resume()
                            }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        if (!change.pressed) {
                            // The slider never saw this press, so it will not call
                            // onValueChangeFinished for it either.
                            if (!startsOnSlider) onValueChangeFinished()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(value = LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (useMaterialYouControls) {
                MaterialYouSlider(
                    modifier = focusModifier,
                    isFocused = isFocused,
                    chapters = chapters,
                    abRepeat = abRepeat,
                    value = displayedPosition,
                    valueRange = 0f..(duration.takeIf { it > 0 } ?: 0f),
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                )
            } else {
                SimpleSlider(
                    modifier = focusModifier,
                    isFocused = isFocused,
                    chapters = chapters,
                    abRepeat = abRepeat,
                    value = displayedPosition,
                    valueRange = 0f..(duration.takeIf { it > 0 } ?: 0f),
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialYouSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isFocused: Boolean = false,
    chapters: List<Chapter> = emptyList(),
    abRepeat: AbRepeat? = null,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val trackHeight = 8.dp
    val thumbWidth = 4.dp
    val trackThumbGapWidth = 12.dp
    val focusedThumbWidth by animateDpAsState(
        targetValue = if (isFocused) 10.dp else thumbWidth,
        label = "thumbWidth",
    )
    val focusedThumbHeight by animateDpAsState(
        targetValue = if (isFocused) 24.dp else 20.dp,
        label = "thumbHeight",
    )

    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier.height(height = SEEKBAR_TOUCH_TARGET),
        track = { sliderState ->
            val disabledAlpha = 0.4f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height = trackHeight)
                    .abRepeatMarks(
                        repeat = abRepeat,
                        duration = sliderState.valueRange.endInclusive,
                        color = primaryColor,
                    )
                    .chapterGaps(
                        chapters = chapters,
                        valueRange.endInclusive,
                    ),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val playedFraction = ((sliderState.value - min) / range).coerceIn(
                    minimumValue = 0f,
                    maximumValue = 1f,
                )
                val playedPixels = size.width * playedFraction

                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(
                    minimumValue = 0f,
                    size.width,
                )
                val rightStart = (playedPixels + gapHalf).coerceIn(
                    minimumValue = 0f,
                    size.width,
                )

                // Inactive track left side
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(
                            x = 0f,
                            y = 0f,
                        ),
                        size = Size(
                            width = leftEnd,
                            size.height,
                        ),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }

                // Inactive track right side
                if (rightStart < size.width) {
                    drawRoundedRect(
                        offset = Offset(
                            x = rightStart,
                            y = 0f,
                        ),
                        size = Size(
                            width = size.width - rightStart,
                            size.height,
                        ),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = endCornerRadius,
                    )
                }

                // Active track
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(
                            x = 0f,
                            y = 0f,
                        ),
                        size = Size(
                            width = leftEnd,
                            size.height,
                        ),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .width(width = focusedThumbWidth)
                    .height(height = focusedThumbHeight)
                    .background(
                        color = primaryColor,
                        shape = CircleShape,
                    )
                    .then(
                        other = if (isFocused) {
                            Modifier.border(
                                2.dp,
                                Color.White,
                                shape = CircleShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        },
    )
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(
        x = startCornerRadius,
        y = startCornerRadius,
    )
    val endCorner = CornerRadius(
        x = endCornerRadius,
        y = endCornerRadius,
    )
    val track = RoundRect(
        rect = Rect(
            offset = Offset(
                offset.x,
                y = 0f,
            ),
            size = Size(
                size.width,
                size.height,
            ),
        ),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply { addRoundRect(roundRect = track) },
        color = color,
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isFocused: Boolean = false,
    chapters: List<Chapter> = emptyList(),
    abRepeat: AbRepeat? = null,
) {
    val thumbSize by animateDpAsState(
        targetValue = if (isFocused) 22.dp else 16.dp,
        label = "thumbSize",
    )
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(SEEKBAR_TOUCH_TARGET),
        thumb = {
            Box(
                modifier = Modifier
                    .size(size = thumbSize)
                    .shadow(
                        4.dp,
                        shape = CircleShape,
                    )
                    .background(Color.White)
                    .then(
                        other = if (isFocused) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .abRepeatMarks(
                        repeat = abRepeat,
                        duration = valueRange.endInclusive,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    .chapterGaps(
                        chapters = chapters,
                        valueRange.endInclusive,
                    )
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = 0.5f)),
            ) {
                if (valueRange.endInclusive > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = value / valueRange.endInclusive)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
    )
}

@OptIn(UnstableApi::class)
private fun Modifier.chapterGaps(chapters: List<Chapter>, duration: Float): Modifier =
    drawWithCache {
        val gaps = Path()
        if (duration > 0f) {
            val halfGap = 1.5.dp.toPx()
            chapters.forEach { chapter ->
                if (chapter.startTimeMs > 0 && chapter.startTimeMs < duration) {
                    val x = size.width * (chapter.startTimeMs / duration)
                    gaps.addRect(
                        rect = Rect(
                            left = x - halfGap,
                            top = 0f,
                            right = x + halfGap,
                            size.height,
                        ),
                    )
                }
            }
        }
        onDrawWithContent {
            clipPath(
                path = gaps,
                ClipOp.Difference,
            ) { this@onDrawWithContent.drawContent() }
        }
    }

@OptIn(UnstableApi::class)
private fun Modifier.abRepeatMarks(repeat: AbRepeat?, duration: Float, color: Color): Modifier {
    if (repeat == null || duration <= 0f) return this
    return drawWithCache {
        val startX = size.width * (repeat.startMs.toFloat() / duration).coerceIn(
            0f,
            1f,
        )
        val endX = size.width * (repeat.endMs.toFloat() / duration).coerceIn(
            0f,
            1f,
        )
        val strokeWidth = 2.dp.toPx()
        val height = size.height
        onDrawWithContent {
            drawContent()
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(
                    x = startX,
                    y = 0f,
                ),
                size = Size(
                    width = endX - startX,
                    height = height,
                ),
            )
            drawRect(
                color = color,
                topLeft = Offset(
                    x = startX,
                    y = 0f,
                ),
                size = Size(
                    width = strokeWidth,
                    height = height,
                ),
            )
            drawRect(
                color = color,
                topLeft = Offset(
                    x = (endX - strokeWidth).coerceAtLeast(0f),
                    y = 0f,
                ),
                size = Size(
                    width = strokeWidth,
                    height = height,
                ),
            )
        }
    }
}
