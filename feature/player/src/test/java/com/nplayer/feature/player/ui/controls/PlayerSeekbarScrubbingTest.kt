package com.nplayer.feature.player.ui.controls

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.media3.exoplayer.ExoPlayer
import com.nplayer.core.ui.theme.NPlayerTheme
import com.nplayer.feature.player.state.PlayerScrubState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerSeekbarScrubbingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun draggingKeepsPositionUntilRelease() {
        withSeekbar { scrubState, commits ->
            val slider = seekbar()
            slider.performTouchInput {
                down(
                    position = Offset(
                        x = width * 0.2f,
                        y = centerY,
                    ),
                )
                moveTo(
                    position = Offset(
                        x = width * 0.7f,
                        y = centerY,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertTrue(scrubState.isScrubbing)
                assertTrue(commits.isEmpty())
            }
            slider.performTouchInput { up() }
            composeRule.runOnIdle {
                assertFalse(scrubState.isScrubbing)
                assertEquals(
                    1,
                    commits.size,
                )
                assertTrue(
                    commits.single() in 600L..800L,
                )
            }
        }
    }

    @Test
    fun draggingAboveSeekbarCancelsOnRelease() {
        withSeekbar { scrubState, commits ->
            val slider = seekbar()
            slider.performTouchInput {
                down(
                    position = Offset(
                        x = width * 0.2f,
                        y = centerY,
                    ),
                )
                moveTo(
                    position = Offset(
                        x = width * 0.7f,
                        y = -100f,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertTrue(scrubState.isCancelled)
                assertFalse(scrubState.isScrubbing)
            }
            slider.performTouchInput { up() }
            composeRule.runOnIdle {
                assertTrue(commits.isEmpty())
                assertFalse(scrubState.isCancelled)
            }
        }
    }

    @Test
    fun returningToSeekbarRestoresSeek() {
        withSeekbar { scrubState, commits ->
            val slider = seekbar()
            slider.performTouchInput {
                down(
                    position = Offset(
                        x = width * 0.2f,
                        y = centerY,
                    ),
                )
                moveTo(
                    position = Offset(
                        x = width * 0.7f,
                        y = -100f,
                    ),
                )
            }
            composeRule.runOnIdle { assertTrue(scrubState.isCancelled) }
            slider.performTouchInput {
                moveTo(
                    position = Offset(
                        x = width * 0.8f,
                        y = centerY,
                    ),
                )
            }
            composeRule.runOnIdle {
                assertFalse(scrubState.isCancelled)
                assertTrue(scrubState.isScrubbing)
            }
            slider.performTouchInput { up() }
            composeRule.runOnIdle {
                assertEquals(
                    1,
                    commits.size,
                )
                assertTrue(
                    commits.single() in 700L..900L,
                )
            }
        }
    }

    private fun seekbar() = composeRule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
    )

    private fun withSeekbar(check: (PlayerScrubState, MutableList<Long>) -> Unit) {
        val player = composeRule.runOnIdle {
            ExoPlayer.Builder(composeRule.activity).build()
        }
        val scrubState = PlayerScrubState(player = player)
        val commits = mutableListOf<Long>()
        try {
            composeRule.setContent {
                NPlayerTheme {
                    PlayerSeekbar(
                        scrubState = scrubState,
                        position = 100f,
                        duration = 1_000f,
                        chapters = emptyList(),
                        onSeekCommit = { commits += it },
                    )
                }
            }
            check(
                scrubState,
                commits,
            )
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }
}
