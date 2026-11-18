package com.nplayer.feature.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.nplayer.core.common.extensions.isTelevision
import com.nplayer.core.ui.components.requestFocusUntilLanded
import com.nplayer.core.ui.components.thenIf
import com.nplayer.feature.player.state.ControlsVisibilityState
import com.nplayer.feature.player.state.SeekGestureState
import com.nplayer.feature.player.state.TapGestureState
import com.nplayer.feature.player.state.VideoZoomAndContentScaleState
import com.nplayer.feature.player.state.rememberAbRepeatState
import com.nplayer.feature.player.state.rememberChaptersState
import com.nplayer.feature.player.state.rememberMetadataState
import com.nplayer.feature.player.state.rememberPlayerKeyInputState
import com.nplayer.feature.player.ui.DpadSeekIndicator
import com.nplayer.feature.player.ui.OverlayShowView
import com.nplayer.feature.player.ui.OverlayView
import com.nplayer.feature.player.ui.PlayerGestureIndicators
import kotlin.time.Duration.Companion.seconds

/** Controls and overlays shared by live playback and the previewable content. */
@OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerControls(
    player: Player,
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    isPipSupported: Boolean,
    onPictureInPictureClick: () -> Unit,
    modifier: Modifier = Modifier,
    dpadUpDown: Int? = null,
) {
    val playerPreferences = state.playerPreferences
    val metadataState = rememberMetadataState(player = player)
    val progressState = rememberProgressStateWithTickInterval(player = player)
    val chaptersState = rememberChaptersState(
        player = player,
        progressState = progressState,
    )
    val abRepeatState = rememberAbRepeatState(player = player)
    val keyInputState = rememberPlayerKeyInputState(
        player = player,
        controls = controlsVisibilityState,
        seekIncrementMs = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
        dpadUpDown = dpadUpDown,
    )
    var overlayView by remember { mutableStateOf<OverlayView?>(value = null) }
    LaunchedEffect(metadataState.mediaId) {
        if (overlayView == OverlayView.CHAPTERS) overlayView = null
    }

    fun showOverlay(overlay: OverlayView) {
        controlsVisibilityState.hideControls()
        overlayView = overlay
    }

    val context = LocalContext.current
    val isTv = remember { context.isTelevision }
    val handleDpadKeys = isTv || dpadUpDown != null
    val rootFocusRequester = remember { FocusRequester() }
    val middleControlsFocusRequester = remember { FocusRequester() }
    val unlockFocusRequester = remember { FocusRequester() }
    var isMiddleControlsFocused by remember { mutableStateOf(value = false) }
    var isUnlockFocused by remember { mutableStateOf(value = false) }
    if (handleDpadKeys) {
        LaunchedEffect(
            controlsVisibilityState.controlsVisible,
            controlsVisibilityState.controlsLocked,
            key3 = overlayView,
        ) {
            if (overlayView != null) return@LaunchedEffect
            if (!isTv || !controlsVisibilityState.controlsVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsVisibilityState.controlsLocked
            val target = if (locked) unlockFocusRequester else middleControlsFocusRequester
            target.requestFocusUntilLanded(attempts = 20) {
                if (locked) isUnlockFocused else isMiddleControlsFocused
            }
        }
    }

    CompositionLocalProvider(
        LocalControlsVisibilityState provides controlsVisibilityState,
        LocalUseMaterialYouControls provides playerPreferences.useMaterialYouControls,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .thenIf(condition = handleDpadKeys) {
                    focusRequester(focusRequester = rootFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (overlayView != null ||
                                (
                                    !isTv && keyEvent.key != Key.DirectionUp &&
                                        keyEvent.key != Key.DirectionDown
                                    )
                            ) {
                                false
                            } else {
                                keyInputState.onKeyEvent(keyEvent = keyEvent)
                            }
                        }
                },
        ) {
            PlayerGestureIndicators(tapGestureState = tapGestureState)
            DpadSeekIndicator(
                visible = keyInputState.isSeeking && keyInputState.seekOffsetMs != 0L,
                offsetMs = keyInputState.seekOffsetMs,
                positionMs = keyInputState.seekPositionMs,
            )

            if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
                PlayerLockedControls(
                    onUnlockControls = controlsVisibilityState::unlockControls,
                    unlockButtonModifier = Modifier.thenIf(condition = isTv) {
                        focusRequester(focusRequester = unlockFocusRequester)
                            .onFocusChanged { isUnlockFocused = it.hasFocus }
                    },
                )
            } else {
                PlayerControls(
                    player = player,
                    title = metadataState.title.orEmpty(),
                    playerPreferences = playerPreferences,
                    videoDecoderMode = state.decoderServiceState.videoMode,
                    controlsVisibilityState = controlsVisibilityState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    progressState = progressState,
                    chaptersState = chaptersState,
                    abRepeatState = abRepeatState,
                    isPipSupported = isPipSupported,
                    onShowOverlay = ::showOverlay,
                    onBackClick = { onAction(PlayerAction.NavigateUp) },
                    onPlayInBackgroundClick = { onAction(PlayerAction.PlayInBackground) },
                    onToggleTimeDisplay = { onAction(PlayerAction.ToggleTimeDisplay) },
                    onPictureInPictureClick = onPictureInPictureClick,
                    middleControlsModifier = Modifier.thenIf(condition = isTv) {
                        focusRequester(focusRequester = middleControlsFocusRequester)
                            .onFocusChanged { isMiddleControlsFocused = it.hasFocus }
                    },
                )
            }

            OverlayShowView(
                player = player,
                overlayView = overlayView,
                chapters = chaptersState.chapters,
                currentChapterIndex = chaptersState.currentChapterIndex,
                abRepeatState = abRepeatState,
                onChapterSelected = { chapter ->
                    if (player.isCurrentMediaItemSeekable) {
                        player.seekTo(chapter.startTimeMs)
                        overlayView = null
                        controlsVisibilityState.showControls()
                    }
                },
                videoDecoderMode = state.decoderServiceState.videoMode,
                audioDecoderMode = state.decoderServiceState.audioMode,
                videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                onDismiss = { overlayView = null },
                onVideoDecoderModeSelected = {
                    onAction(PlayerAction.SetVideoDecoderMode(mode = it))
                },
                onAudioDecoderModeSelected = {
                    onAction(PlayerAction.SetAudioDecoderMode(mode = it))
                },
                onSelectSubtitleClick = { onAction(PlayerAction.SelectSubtitle) },
                onSelectAudioClick = { onAction(PlayerAction.SelectAudio) },
                onSubtitleOptionEvent = {
                    onAction(PlayerAction.OnSubtitleOptionEvent(event = it))
                },
                onVideoContentScaleChanged =
                    videoZoomAndContentScaleState::onVideoContentScaleChanged,
            )
        }
    }

    BackHandler {
        when {
            overlayView != null -> overlayView = null

            isTv && controlsVisibilityState.controlsVisible ->
                controlsVisibilityState.hideControls()

            else -> onAction(PlayerAction.NavigateUp)
        }
    }
}
