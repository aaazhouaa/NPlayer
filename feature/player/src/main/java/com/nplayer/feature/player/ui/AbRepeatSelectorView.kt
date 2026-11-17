package com.nplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getStringForTime
import com.nplayer.core.ui.R
import com.nplayer.feature.player.state.AbRepeatState

private const val ADJUST_STEP_MS = 1_000L

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.AbRepeatSelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    abRepeatState: AbRepeatState,
) {
    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.ab_repeat),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbRepeatPointRow(
                label = stringResource(R.string.ab_repeat_start),
                positionMs = abRepeatState.startMs,
                enabled = abRepeatState.startMs != null,
                onDecrease = { abRepeatState.adjustStart(deltaMs = -ADJUST_STEP_MS) },
                onIncrease = { abRepeatState.adjustStart(deltaMs = ADJUST_STEP_MS) },
            )
            AbRepeatPointRow(
                label = stringResource(R.string.ab_repeat_end),
                positionMs = abRepeatState.endMs,
                enabled = abRepeatState.endMs != null,
                onDecrease = { abRepeatState.adjustEnd(deltaMs = -ADJUST_STEP_MS) },
                onIncrease = { abRepeatState.adjustEnd(deltaMs = ADJUST_STEP_MS) },
            )
            TextButton(
                onClick = abRepeatState::clear,
                enabled = abRepeatState.startMs != null,
                modifier = Modifier.align(alignment = Alignment.End),
            ) {
                Text(text = stringResource(R.string.ab_repeat_clear))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun AbRepeatPointRow(
    label: String,
    positionMs: Long?,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(weight = 1f),
        )
        Text(
            text = positionMs?.let { getStringForTime(it) } ?: "--:--",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(width = 80.dp),
        )
        FilledTonalIconButton(
            onClick = onDecrease,
            enabled = enabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = null,
            )
        }
        FilledTonalIconButton(
            onClick = onIncrease,
            enabled = enabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
            )
        }
    }
}
