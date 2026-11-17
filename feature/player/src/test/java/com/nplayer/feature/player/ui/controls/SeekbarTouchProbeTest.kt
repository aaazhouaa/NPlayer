package com.nplayer.feature.player.ui.controls

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nplayer.core.ui.theme.NextPlayerTheme
import com.nplayer.feature.player.LocalUseMaterialYouControls
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Temporary probe: measures the real touch band instead of assuming it equals the layout box. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeekbarTouchProbeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun probeVerticalTouchBand() {
        for (material in listOf(false, true)) {
            var seeked = false
            var materialControls by mutableStateOf(material)
            composeRule.setContent {
                NextPlayerTheme {
                    CompositionLocalProvider(
                        value = LocalUseMaterialYouControls provides materialControls,
                    ) {
                        // A 200dp-tall host so presses far outside the seekbar still land somewhere.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Color.Black),
                        ) {
                            Box(modifier = Modifier.height(100.dp)) {
                                PlayerSeekbar(
                                    position = 100f,
                                    duration = 1_000f,
                                    chapters = emptyList(),
                                    onSeek = { seeked = true },
                                    onSeekFinished = {},
                                )
                            }
                        }
                    }
                }
            }

            val root = composeRule.onRoot()
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val slider = composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            )
            val sliderBounds = slider.fetchSemanticsNode().boundsInRoot
            println(
                "PROBE material=$material root=$rootBounds " +
                    "slider=$sliderBounds sliderHeight=${sliderBounds.height}",
            )

            // Walk down from above the seekbar to well below it, pressing at each step.
            val startY = sliderBounds.top - 40f
            var lastHit = -999f
            var firstHit = 999f
            var y = startY
            while (y <= sliderBounds.bottom + 40f) {
                var hit = false
                composeRule.setContent {
                    NextPlayerTheme {
                        CompositionLocalProvider(
                            value = LocalUseMaterialYouControls provides materialControls,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(Color.Black),
                            ) {
                                Box(modifier = Modifier.height(100.dp)) {
                                    PlayerSeekbar(
                                        position = 100f,
                                        duration = 1_000f,
                                        chapters = emptyList(),
                                        onSeek = { hit = true },
                                        onSeekFinished = {},
                                    )
                                }
                            }
                        }
                    }
                }
                val sliderNow = composeRule.onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                )
                val b = sliderNow.fetchSemanticsNode().boundsInRoot
                val probeY = b.top + (y - sliderBounds.top)
                sliderNow.performTouchInput {
                    down(position = Offset(x = width * 0.5f, y = probeY - b.top))
                    up()
                }
                composeRule.waitForIdle()
                if (hit) {
                    if (firstHit > 1000f) firstHit = probeY
                    lastHit = probeY
                    println("PROBE material=$material HIT at y=$probeY (sliderTop=${b.top})")
                } else {
                    println("PROBE material=$material miss at y=$probeY (sliderTop=${b.top})")
                }
                y += 4f
            }
            println(
                "PROBE material=$material band= $firstHit .. $lastHit " +
                    "height=${lastHit - firstHit} sliderTop=${sliderBounds.top} " +
                    "sliderBottom=${sliderBounds.bottom}",
            )
        }
    }
}
