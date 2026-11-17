package com.nplayer.navigation

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nplayer.feature.more.navigation.historyEntry
import com.nplayer.feature.more.navigation.logEntry
import com.nplayer.feature.more.navigation.moreEntry
import com.nplayer.feature.more.navigation.navigateToHistory
import com.nplayer.feature.more.navigation.navigateToLogs
import com.nplayer.feature.more.navigation.navigateToTrash
import com.nplayer.feature.more.navigation.trashEntry
import com.nplayer.feature.videopicker.navigation.navigateToVault
import com.nplayer.feature.videopicker.navigation.vaultEntry
import com.nplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.moreNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    moreEntry(
        onHistoryClick = backStack::navigateToHistory,
        onPlayVideo = { context.startPlayback(it.toUri()) },
        onSettingsClick = backStack::navigateToSettings,
        onTrashClick = backStack::navigateToTrash,
        onVaultClick = backStack::navigateToVault,
        onLogsClick = backStack::navigateToLogs,
    )

    historyEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )

    trashEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )

    logEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )

    vaultEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        // Vault files are served through FileProvider, so read access must be granted at
        // playback time for both PlayerActivity and the (separate) PlayerService component.
        onPlayVideo = { uri ->
            context.startPlayback(
                uri = uri,
                grantReadPermission = true,
            )
        },
        onPlayVideos = { uris ->
            context.startPlayback(
                uris = uris,
                grantReadPermission = true,
            )
        },
    )
}
