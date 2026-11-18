package com.nplayer.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nplayer.settings.Setting
import com.nplayer.settings.navigation.aboutPreferencesEntry
import com.nplayer.settings.navigation.appearancePreferencesEntry
import com.nplayer.settings.navigation.audioPreferencesEntry
import com.nplayer.settings.navigation.folderPreferencesEntry
import com.nplayer.settings.navigation.generalPreferencesEntry
import com.nplayer.settings.navigation.gesturePreferencesEntry
import com.nplayer.settings.navigation.librariesEntry
import com.nplayer.settings.navigation.logViewerEntry
import com.nplayer.settings.navigation.mediaLibraryPreferencesEntry
import com.nplayer.settings.navigation.navigateToAboutPreferences
import com.nplayer.settings.navigation.navigateToAppearancePreferences
import com.nplayer.settings.navigation.navigateToAudioPreferences
import com.nplayer.settings.navigation.navigateToFolderPreferencesScreen
import com.nplayer.settings.navigation.navigateToGeneralPreferences
import com.nplayer.settings.navigation.navigateToGesturePreferences
import com.nplayer.settings.navigation.navigateToLibraries
import com.nplayer.settings.navigation.navigateToLogViewer
import com.nplayer.settings.navigation.navigateToMediaLibraryPreferencesScreen
import com.nplayer.settings.navigation.navigateToPlayerPreferences
import com.nplayer.settings.navigation.navigateToSubtitlePreferences
import com.nplayer.settings.navigation.navigateToThumbnailPreferencesScreen
import com.nplayer.settings.navigation.playerPreferencesEntry
import com.nplayer.settings.navigation.settingsEntry
import com.nplayer.settings.navigation.subtitlePreferencesEntry
import com.nplayer.settings.navigation.thumbnailPreferencesEntry

fun EntryProviderScope<NavKey>.settingsNavGraph(backStack: NavBackStack<NavKey>) {
    settingsEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onItemClick = { setting ->
            when (setting) {
                Setting.APPEARANCE -> backStack.navigateToAppearancePreferences()
                Setting.MEDIA_LIBRARY -> backStack.navigateToMediaLibraryPreferencesScreen()
                Setting.PLAYER -> backStack.navigateToPlayerPreferences()
                Setting.GESTURES -> backStack.navigateToGesturePreferences()
                Setting.AUDIO -> backStack.navigateToAudioPreferences()
                Setting.SUBTITLE -> backStack.navigateToSubtitlePreferences()
                Setting.GENERAL -> backStack.navigateToGeneralPreferences()
                Setting.ABOUT -> backStack.navigateToAboutPreferences()
            }
        },
    )
    appearancePreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    mediaLibraryPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onFolderSettingClick = backStack::navigateToFolderPreferencesScreen,
        onThumbnailSettingClick = backStack::navigateToThumbnailPreferencesScreen,
    )
    thumbnailPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    folderPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    playerPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    gesturePreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    audioPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    subtitlePreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    generalPreferencesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onLogsClick = backStack::navigateToLogViewer,
    )
    logViewerEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    aboutPreferencesEntry(
        onLibrariesClick = backStack::navigateToLibraries,
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
    librariesEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )
}
