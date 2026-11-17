package com.nplayer.core.ui.composables

import android.Manifest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nplayer.core.ui.R
import com.nplayer.core.ui.preview.DayNightPreview
import com.nplayer.core.ui.theme.NPlayerTheme

@Composable
fun PermissionRationaleDialog(
    text: String,
    modifier: Modifier = Modifier,
    onConfirmButtonClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.permission_request),
            )
        },
        text = { Text(text = text) },
        confirmButton = {
            Button(onClick = onConfirmButtonClick) {
                Text(text = stringResource(R.string.grant_permission))
            }
        },
    )
}

@DayNightPreview
@Composable
fun PermissionRationaleDialogPreview() {
    NPlayerTheme {
        Surface {
            PermissionRationaleDialog(
                text = stringResource(
                    id = R.string.permission_info,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
                onConfirmButtonClick = {},
            )
        }
    }
}
