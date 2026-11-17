package com.nplayer.feature.player.state

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.nplayer.feature.player.model.AbRepeat
import com.nplayer.feature.player.service.getAbRepeat
import com.nplayer.feature.player.service.setAbRepeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun rememberAbRepeatState(player: Player?): AbRepeatState {
    val scope = rememberCoroutineScope()
    val state = remember(player) {
        AbRepeatState(
            player = player,
            scope = scope,
        )
    }
    LaunchedEffect(key1 = player) { state.initialize() }
    return state
}

/**
 * Marks a region on the timeline: the first toggle places [startMs], the second places the end
 * point, and the third clears it. Until both points exist the region only lives here.
 */
@OptIn(UnstableApi::class)
@Stable
class AbRepeatState(private val player: Player?, private val scope: CoroutineScope) {
    var repeat: AbRepeat? by mutableStateOf(value = null)
        private set

    /** Position of the A point while the B point is still missing. */
    var pendingStartMs: Long? by mutableStateOf(value = null)
        private set

    val startMs: Long?
        get() = repeat?.startMs ?: pendingStartMs

    val endMs: Long?
        get() = repeat?.endMs

    suspend fun initialize() {
        val controller = player as? MediaController ?: return
        repeat = controller.getAbRepeat()
        controller.listen { events ->
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                scope.launch { repeat = controller.getAbRepeat() }
                pendingStartMs = null
            }
        }
    }

    fun toggle(positionMs: Long) {
        val current = repeat
        val pending = pendingStartMs
        when {
            current != null -> clear()

            pending == null -> pendingStartMs = positionMs

            positionMs > pending -> setRepeat(
                startMs = pending,
                endMs = positionMs,
            )

            else -> Unit
        }
    }

    fun adjustStart(deltaMs: Long) {
        val current = repeat
        if (current == null) {
            val pending = pendingStartMs ?: return
            pendingStartMs = (pending + deltaMs).coerceAtLeast(minimumValue = 0L)
            return
        }
        val maxStart = maxOf(
            0L,
            current.endMs - MINIMUM_DURATION_MS,
        )
        val startMs = (current.startMs + deltaMs).coerceIn(
            minimumValue = 0L,
            maximumValue = maxStart,
        )
        if (startMs != current.startMs) {
            setRepeat(
                startMs = startMs,
                endMs = current.endMs,
            )
        }
    }

    fun adjustEnd(deltaMs: Long) {
        val current = repeat ?: return
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val endMs = (current.endMs + deltaMs).coerceIn(
            minimumValue = current.startMs + MINIMUM_DURATION_MS,
            maximumValue = maxOf(
                duration,
                current.startMs + MINIMUM_DURATION_MS,
            ),
        )
        if (endMs != current.endMs) {
            setRepeat(
                startMs = current.startMs,
                endMs = endMs,
            )
        }
    }

    fun clear() {
        repeat = null
        pendingStartMs = null
        (player as? MediaController)?.setAbRepeat(repeat = null)
    }

    private fun setRepeat(startMs: Long, endMs: Long) {
        repeat = AbRepeat(
            startMs = startMs,
            endMs = endMs,
        )
        pendingStartMs = null
        (player as? MediaController)?.setAbRepeat(
            repeat = AbRepeat(
                startMs = startMs,
                endMs = endMs,
            ),
        )
    }

    companion object {
        const val MINIMUM_DURATION_MS = 1_000L
    }
}
