package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * Keeps the back stack entries below the top entry of the current tab composed, so revealing
 * them on back navigation reuses their composition instead of rebuilding it mid-animation.
 *
 * Only the top entry is reported as [Scene.entries]: NavDisplay renders a shared entry solely in
 * the scene that claims it, so the entries below the top must stay unclaimed. While another scene
 * is transitioning in on top of this one, the entries below the top are not rendered here
 * either, because the outgoing scene already renders them; their composition moves into this
 * scene once the transition settles.
 */
class StackedSceneStrategy(private val tabRootKeys: Set<Any>) : SceneStrategy<NavKey> {

    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        if (entries.size < 2) return null
        val tabRootIndex = entries.indexOfLast { it.contentKey in tabRootKeys }
        if (tabRootIndex < 0 || tabRootIndex >= entries.lastIndex) return null

        return StackedScene(
            key = entries.last().contentKey,
            top = entries.last(),
            underlaidEntries = entries.subList(
                tabRootIndex,
                entries.lastIndex,
            ).toList(),
            previousEntries = entries.dropLast(1),
        )
    }
}

private class StackedScene(
    override val key: Any,
    private val top: NavEntry<NavKey>,
    private val underlaidEntries: List<NavEntry<NavKey>>,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {

    override val entries: List<NavEntry<NavKey>> = listOf(top)

    override val content: @Composable () -> Unit = {
        if (!isIncomingTransitionTarget()) {
            underlaidEntries.forEach { entry -> entry.Content() }
        }
        top.Content()
    }

    @Composable
    private fun isIncomingTransitionTarget(): Boolean {
        val visibility = LocalNavAnimatedContentScope.current.transition

        @Suppress("UNCHECKED_CAST")
        val scenes = visibility.parentTransition as? Transition<Scene<NavKey>> ?: return false
        return scenes.targetState === this && scenes.currentState != scenes.targetState
    }
}
