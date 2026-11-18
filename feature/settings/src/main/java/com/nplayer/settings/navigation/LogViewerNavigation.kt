package com.nplayer.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nplayer.settings.screens.logs.LogViewerScreen
import kotlinx.serialization.Serializable

@Serializable
object LogViewerRoute : NavKey

fun NavBackStack<NavKey>.navigateToLogViewer() {
    add(element = LogViewerRoute)
}

fun EntryProviderScope<NavKey>.logViewerEntry(onNavigateUp: () -> Unit) {
    entry<LogViewerRoute> {
        LogViewerScreen(onNavigateUp = onNavigateUp)
    }
}
