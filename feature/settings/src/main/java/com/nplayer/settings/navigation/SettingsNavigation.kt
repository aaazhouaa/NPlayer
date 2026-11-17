package com.nplayer.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nplayer.settings.Setting
import com.nplayer.settings.SettingsOutput
import com.nplayer.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute : NavKey

fun NavBackStack<NavKey>.navigateToSettings() {
    add(element = SettingsRoute)
}

fun EntryProviderScope<NavKey>.settingsEntry(
    onNavigateUp: () -> Unit,
    onItemClick: (Setting) -> Unit,
) {
    entry<SettingsRoute> {
        SettingsScreen(
            output = SettingsOutput(
                navigateUp = onNavigateUp,
                openSetting = onItemClick,
            ),
        )
    }
}
