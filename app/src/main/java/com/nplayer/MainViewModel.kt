package com.nplayer

import androidx.lifecycle.viewModelScope
import com.nplayer.core.common.Logger
import com.nplayer.core.data.repository.PreferencesRepository
import com.nplayer.core.model.ApplicationPreferences
import com.nplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val preferencesRepository: PreferencesRepository) :
    MviViewModel<MainActivityUiState, Nothing>() {

    private val stateInternal =
        MutableStateFlow<MainActivityUiState>(value = MainActivityUiState.Loading)
    override val state: StateFlow<MainActivityUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                stateInternal.update { MainActivityUiState.Success(preferences = preferences) }
            }
        }
        // Debug entries stay out of the session file unless the user asks for them, so the
        // preference has to reach the logger as soon as it loads.
        viewModelScope.launch {
            preferencesRepository.applicationPreferences
                .map { preferences -> preferences.isVerboseLogging }
                .distinctUntilChanged()
                .collect { enabled -> Logger.setVerboseLogging(enabled = enabled) }
        }
    }

    // Application preferences are observed only; this view model has no input actions.
    override fun onAction(action: Nothing) = Unit
}

sealed interface MainActivityUiState {
    object Loading : MainActivityUiState
    data class Success(val preferences: ApplicationPreferences) : MainActivityUiState
}
