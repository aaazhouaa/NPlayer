package com.nplayer.feature.videopicker.screens

import com.nplayer.core.model.Folder

sealed interface MediaState {
    data object Loading : MediaState
    data class Success(val data: Folder?) : MediaState
}
