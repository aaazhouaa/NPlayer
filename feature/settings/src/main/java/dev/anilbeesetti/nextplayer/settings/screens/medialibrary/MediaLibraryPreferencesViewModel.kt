package dev.anilbeesetti.nextplayer.settings.screens.medialibrary

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class MediaLibraryPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val systemService: SystemService,
    private val mediaRepository: MediaRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<MediaLibraryPreferencesUiState, MediaLibraryPreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val openFolders: () -> Unit,
        val openThumbnails: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(value = MediaLibraryPreferencesUiState())
    override val state: StateFlow<MediaLibraryPreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                stateInternal.update { currentState -> currentState.copy(preferences = it) }
            }
        }
    }

    override fun onAction(action: MediaLibraryPreferencesUiEvent) {
        when (action) {
            is MediaLibraryPreferencesUiEvent.NavigateUp -> output.navigateUp()

            is MediaLibraryPreferencesUiEvent.OpenFolders -> output.openFolders()

            is MediaLibraryPreferencesUiEvent.OpenThumbnails -> output.openThumbnails()

            is MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia ->
                toggleMarkLastPlayedMedia()

            is MediaLibraryPreferencesUiEvent.SetWatchHistoryEnabled -> setWatchHistoryEnabled(
                action.enabled,
            )

            is MediaLibraryPreferencesUiEvent.ToggleIncludeNetworkWatchHistory ->
                toggleIncludeNetworkWatchHistory()

            is MediaLibraryPreferencesUiEvent.UpdateNewVideoThreshold -> updateNewVideoThreshold(
                action.days,
            )

            is MediaLibraryPreferencesUiEvent.ToggleShowHiddenFiles -> toggleShowHiddenFiles()

            is MediaLibraryPreferencesUiEvent.ToggleRespectNoMedia -> toggleRespectNoMedia()

            is MediaLibraryPreferencesUiEvent.AddScanFolder -> addScanFolder()

            is MediaLibraryPreferencesUiEvent.RemoveScanFolder -> removeScanFolder(action.path)
        }
    }

    private fun toggleMarkLastPlayedMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(markLastPlayedMedia = !it.markLastPlayedMedia)
            }
        }
    }

    private fun setWatchHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(isHistoryPaused = !enabled)
            }
            if (!enabled) mediaRepository.clearPlaybackHistory()
        }
    }

    private fun toggleIncludeNetworkWatchHistory() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(includeNetworkWatchHistory = !it.includeNetworkWatchHistory)
            }
        }
    }

    private fun updateNewVideoThreshold(days: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    newVideoThresholdDays = days.coerceIn(
                        minimumValue = 0,
                        maximumValue = 30,
                    ),
                )
            }
        }
    }

    private fun toggleShowHiddenFiles() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(showHiddenFiles = !it.showHiddenFiles)
            }
        }
    }

    private fun toggleRespectNoMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(respectNoMedia = !it.respectNoMedia)
            }
        }
    }

    private fun addScanFolder() {
        viewModelScope.launch {
            val path = systemService.pickFolderPath()
            if (path != null) {
                preferencesRepository.updateApplicationPreferences {
                    val folders = if (path in
                        it.scanFolders
                    ) {
                        it.scanFolders
                    } else {
                        it.scanFolders + path
                    }
                    it.copy(scanFolders = folders)
                }
            }
        }
    }

    private fun removeScanFolder(path: String) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(scanFolders = it.scanFolders - path)
            }
        }
    }
}

data class MediaLibraryPreferencesUiState(
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface MediaLibraryPreferencesUiEvent {
    data object NavigateUp : MediaLibraryPreferencesUiEvent
    data object OpenFolders : MediaLibraryPreferencesUiEvent
    data object OpenThumbnails : MediaLibraryPreferencesUiEvent

    data object ToggleMarkLastPlayedMedia : MediaLibraryPreferencesUiEvent
    data class SetWatchHistoryEnabled(val enabled: Boolean) : MediaLibraryPreferencesUiEvent
    data object ToggleIncludeNetworkWatchHistory : MediaLibraryPreferencesUiEvent
    data class UpdateNewVideoThreshold(val days: Int) : MediaLibraryPreferencesUiEvent

    data object ToggleShowHiddenFiles : MediaLibraryPreferencesUiEvent
    data object ToggleRespectNoMedia : MediaLibraryPreferencesUiEvent
    data object AddScanFolder : MediaLibraryPreferencesUiEvent
    data class RemoveScanFolder(val path: String) : MediaLibraryPreferencesUiEvent
}
