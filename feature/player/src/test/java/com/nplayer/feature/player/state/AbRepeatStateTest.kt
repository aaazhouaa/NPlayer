package com.nplayer.feature.player.state

import com.nplayer.feature.player.model.AbRepeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbRepeatStateTest {
    private val state = AbRepeatState(
        player = null,
        scope = CoroutineScope(context = Dispatchers.Unconfined),
    )

    @Test
    fun thirdToggleClearsTheMarkedRegion() {
        state.toggle(positionMs = 10_000)
        assertNull(state.repeat)
        assertEquals(
            10_000L,
            state.startMs,
        )

        state.toggle(positionMs = 20_000)
        assertEquals(
            AbRepeat(
                startMs = 10_000,
                endMs = 20_000,
            ),
            state.repeat,
        )
        assertNull(state.pendingStartMs)

        state.toggle(positionMs = 30_000)
        assertNull(state.repeat)
        assertNull(state.startMs)
    }

    @Test
    fun endPointBeforeStartPointIsIgnored() {
        state.toggle(positionMs = 10_000)
        state.toggle(positionMs = 5_000)
        assertNull(state.repeat)
        assertEquals(
            10_000L,
            state.startMs,
        )
    }

    @Test
    fun adjustingStartKeepsTheRegionAtLeastOneSecondLong() {
        state.toggle(positionMs = 10_000)
        state.toggle(positionMs = 20_000)

        state.adjustStart(deltaMs = 30_000)

        assertEquals(
            AbRepeat(
                startMs = 19_000,
                endMs = 20_000,
            ),
            state.repeat,
        )
    }

    @Test
    fun adjustingEndKeepsTheRegionAtLeastOneSecondLong() {
        state.toggle(positionMs = 10_000)
        state.toggle(positionMs = 20_000)

        state.adjustEnd(deltaMs = -30_000)

        assertEquals(
            AbRepeat(
                startMs = 10_000,
                endMs = 11_000,
            ),
            state.repeat,
        )
    }

    @Test
    fun pendingStartCanBeAdjustedBeforeTheEndPointExists() {
        state.toggle(positionMs = 10_000)

        state.adjustStart(deltaMs = -2_000)

        assertEquals(
            8_000L,
            state.startMs,
        )
        assertNull(state.repeat)
    }
}
