package com.nplayer.feature.player.ui.controls

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.media3.extractor.metadata.Chapter
import com.nplayer.core.ui.theme.NPlayerTheme
import com.nplayer.feature.player.LocalUseMaterialYouControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerSeekbarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pressesWellAboveAndBelowTheTrackStillSeek() {
        // A fingertip covers 8-10mm, so presses land well off the drawn 4-8dp track. The Material
        // Slider only accepts presses within its own 48dp interactive area, so the padding above
        // and below has to be handled by the seekbar container instead of falling through to the
        // gesture layer, which would read the press as a tap on the video.
        var materialControls by mutableStateOf(value = false)
        var hits = 0
        composeRule.setContent {
            NPlayerTheme {
                CompositionLocalProvider(
                    value = LocalUseMaterialYouControls provides materialControls,
                ) {
                    PlayerSeekbar(
                        position = 100f,
                        duration = 1_000f,
                        chapters = emptyList(),
                        onSeek = { hits++ },
                        onSeekFinished = {},
                    )
                }
            }
        }

        val root = composeRule.onRoot()
        for (material in listOf(
            false,
            true,
        )) {
            composeRule.runOnIdle { materialControls = material }
            val center = root.fetchSemanticsNode().boundsInRoot.center

            // 30dp from the center sits outside the slider's own 48dp band but inside the
            // seekbar's taller touch target.
            for (offset in listOf(
                -30f,
                -26f,
                26f,
                30f,
            )) {
                composeRule.runOnIdle { hits = 0 }
                root.performTouchInput {
                    down(
                        position = Offset(
                            x = width * 0.5f,
                            y = center.y + offset,
                        ),
                    )
                    up()
                }
                composeRule.waitForIdle()
                assertEquals(
                    "style material=$material, offset=$offset",
                    1,
                    composeRule.runOnIdle { hits },
                )
            }
        }
    }

    @Test
    fun bothStylesTickOnlyWhenScrubbingAcrossChapters() {
        val ticks = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks += hapticFeedbackType
            }
        }
        var position by mutableFloatStateOf(value = 100f)
        var materialControls by mutableStateOf(value = false)
        var chapters by mutableStateOf(
            listOf(
                0L,
                400L,
                700L,
            ).map { Chapter.Builder().setStartTimeMs(it).build() },
        )
        composeRule.setContent {
            NPlayerTheme {
                CompositionLocalProvider(
                    LocalHapticFeedback provides haptics,
                    LocalUseMaterialYouControls provides materialControls,
                ) {
                    PlayerSeekbar(
                        position = position,
                        duration = 1_000f,
                        chapters = chapters,
                        onSeek = { position = it },
                        onSeekFinished = {},
                    )
                }
            }
        }

        val slider = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        )
        for (material in listOf(
            false,
            true,
        )) {
            composeRule.runOnIdle {
                materialControls = material
                position = 100f
                ticks.clear()
            }
            slider.performTouchInput {
                down(
                    position = Offset(
                        x = width * 0.1f,
                        y = centerY,
                    ),
                )
                moveTo(
                    position = Offset(
                        x = width * 0.2f,
                        y = centerY,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertEquals(
                    0,
                    ticks.size,
                )
            }
            slider.performTouchInput {
                moveTo(
                    position = Offset(
                        x = width * 0.5f,
                        y = centerY,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertEquals(
                    listOf(HapticFeedbackType.SegmentTick),
                    ticks,
                )
            }
            slider.performTouchInput {
                moveTo(
                    position = Offset(
                        x = width * 0.6f,
                        y = centerY,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertEquals(
                    1,
                    ticks.size,
                )
            }
            slider.performTouchInput {
                moveTo(
                    position = Offset(
                        x = width * 0.2f,
                        y = centerY,
                    ),
                )
                up()
            }
            composeRule.runOnIdle {
                assertEquals(
                    2,
                    ticks.size,
                )
                position = 800f
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    2,
                    ticks.size,
                )
            }
            slider.performTouchInput {
                down(
                    position = Offset(
                        x = width * 0.8f,
                        y = centerY,
                    ),
                )
                moveTo(
                    position = Offset(
                        x = width * 0.9f,
                        y = centerY,
                    ),
                )
                up()
            }
            composeRule.runOnIdle {
                assertEquals(
                    2,
                    ticks.size,
                )
            }
        }

        composeRule.runOnIdle {
            chapters = emptyList()
            ticks.clear()
        }
        slider.performTouchInput {
            down(
                position = Offset(
                    x = width * 0.9f,
                    y = centerY,
                ),
            )
            moveTo(
                position = Offset(
                    x = width * 0.1f,
                    y = centerY,
                ),
            )
            up()
        }
        composeRule.runOnIdle {
            assertEquals(
                0,
                ticks.size,
            )
        }
    }
}
