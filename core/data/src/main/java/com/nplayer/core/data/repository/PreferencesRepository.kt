package com.nplayer.core.data.repository

import com.nplayer.core.model.ApplicationPreferences
import com.nplayer.core.model.PlayerPreferences
import kotlinx.coroutines.flow.StateFlow

interface PreferencesRepository {

    /**
     * Stream of [ApplicationPreferences].
     */
    val applicationPreferences: StateFlow<ApplicationPreferences>

    /**
     * Stream of [PlayerPreferences].
     */
    val playerPreferences: StateFlow<PlayerPreferences>

    suspend fun updateApplicationPreferences(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    )

    suspend fun updatePlayerPreferences(transform: suspend (PlayerPreferences) -> PlayerPreferences)

    suspend fun resetPreferences()
}
