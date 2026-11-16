package dev.anilbeesetti.nextplayer.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A stacked scene renders the entries below its top itself, so an entry that another scene also
 * renders during a transition would compose twice and register its SaveableStateProvider key
 * twice, crashing with "Key ... was used multiple times". Every push and pop must keep each entry
 * in exactly one scene.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StackedSceneStrategyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pushAndPopComposeEveryEntryExactlyOnce() {
        val backStack = NavBackStack<NavKey>(TabRoot)
        composeRule.setContent {
            NavDisplay(
                backStack = backStack,
                sceneStrategies = listOf(StackedSceneStrategy(tabRootKeys)),
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider {
                    entry<TabRoot> { Text(text = "root") }
                    entry<Detail> { key -> Text(text = "detail ${key.id}") }
                },
            )
        }
        composeRule.onNodeWithText(text = "root").assertExists()

        composeRule.runOnIdle { backStack.add(element = Detail(id = 1)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text = "detail 1").assertExists()

        composeRule.runOnIdle { backStack.removeLastOrNull() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text = "root").assertExists()
    }

    @Test
    fun nestedPushComposesEveryEntryExactlyOnce() {
        val backStack = NavBackStack<NavKey>(TabRoot)
        composeRule.setContent {
            NavDisplay(
                backStack = backStack,
                sceneStrategies = listOf(StackedSceneStrategy(tabRootKeys)),
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider {
                    entry<TabRoot> { Text(text = "root") }
                    entry<Detail> { key -> Text(text = "detail ${key.id}") }
                },
            )
        }

        composeRule.runOnIdle {
            backStack.add(element = Detail(id = 1))
            backStack.add(element = Detail(id = 2))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text = "detail 2").assertExists()

        composeRule.runOnIdle { backStack.removeLastOrNull() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text = "detail 1").assertExists()
    }

    private val tabRootKeys = setOf(
        TabRoot,
        NavEntry(key = TabRoot) {}.contentKey,
    )

    @Serializable
    private data object TabRoot : NavKey

    @Serializable
    private data class Detail(val id: Int) : NavKey
}
