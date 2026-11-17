package com.nplayer.feature.player.buttons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nplayer.core.ui.R
import com.nplayer.feature.player.state.AbRepeatState

@Composable
fun AbRepeatButton(
    modifier: Modifier = Modifier,
    abRepeatState: AbRepeatState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val description = stringResource(R.string.ab_repeat)
    val label = if (abRepeatState.pendingStartMs != null) "A-" else "A-B"
    PlayerButton(
        modifier = modifier.semantics { contentDescription = description },
        onClick = onClick,
        onLongClick = onLongClick,
        containerColor = PlayerButtonBlackAlpha,
        contentPadding = PaddingValues(4.dp),
    ) {
        val contentColor = if (abRepeatState.repeat != null) {
            MaterialTheme.colorScheme.primary
        } else {
            LocalContentColor.current
        }
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = contentColor,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                    minFontSize = 6.sp,
                    stepSize = 0.5.sp,
                ),
            )
        }
    }
}
