package com.nplayer.feature.videopicker.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nplayer.core.model.MediaLayoutMode
import com.nplayer.core.ui.R

@Composable
fun MediaLayoutMode.name(): String = when (this) {
    MediaLayoutMode.LIST -> stringResource(id = R.string.list)
    MediaLayoutMode.GRID -> stringResource(id = R.string.grid)
}
