package dev.anilbeesetti.nextplayer.feature.more.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.common.LogSession
import dev.anilbeesetti.nextplayer.core.common.LogStore
import dev.anilbeesetti.nextplayer.core.common.copyLogReportToClipboard
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the retained log sessions, newest first, with the full text of the selected one. Crashes
 * are written into the same sessions, so there is no separate crash dialog.
 */
@Composable
fun LogViewerScreen(onNavigateUp: () -> Unit) {
    val context = LocalContext.current
    val logStore = remember(context) { LogStore.create(context) }
    var selected by remember { mutableStateOf<LogSession?>(null) }
    var refreshKey by remember { mutableStateOf(value = 0) }
    val summaries by produceState(
        initialValue = emptyList<LogSummary>(),
        key1 = refreshKey,
    ) {
        value = withContext(Dispatchers.IO) {
            logStore.sessions().map { session ->
                LogSummary(
                    session = session,
                    containsCrash = logStore.read(session).contains(other = CRASH_MARKER),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.logs_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = scaffoldPadding.copy(bottom = 0.dp)),
        ) {
            if (summaries.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(all = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(weight = 1f)) {
                    items(
                        items = summaries,
                        key = { it.session.name },
                    ) { summary ->
                        LogSessionItem(
                            summary = summary,
                            selected = summary.session == selected,
                            onClick = { selected = summary.session },
                        )
                    }
                }
                selected?.let { session ->
                    LogSessionContent(
                        logStore = logStore,
                        session = session,
                        onDeleted = {
                            selected = null
                            refreshKey++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogSessionContent(logStore: LogStore, session: LogSession, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val content by produceState<String?>(
        initialValue = null,
        key1 = session,
    ) {
        value = withContext(Dispatchers.IO) { logStore.read(session) }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = content != null,
                onClick = {
                    content?.let { text ->
                        copyLogReportToClipboard(
                            context.getSystemService(ClipboardManager::class.java),
                            text,
                        )
                    }
                },
            ) {
                Text(text = stringResource(R.string.logs_copy))
            }
            TextButton(
                enabled = content != null,
                onClick = { content?.let { text -> context.shareLogText(text) } },
            ) {
                Text(text = stringResource(R.string.logs_share))
            }
            TextButton(
                onClick = {
                    logStore.delete(session)
                    onDeleted()
                },
            ) {
                Text(text = stringResource(R.string.logs_delete))
            }
        }
        when (val text = content) {
            null -> CircularProgressIndicator(modifier = Modifier.size(size = 24.dp))

            else -> SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier
                        .weight(weight = 1f)
                        .fillMaxWidth()
                        .verticalScroll(state = rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun LogSessionItem(summary: LogSummary, selected: Boolean, onClick: () -> Unit) {
    val timestamp = remember(summary.session.lastModifiedMillis) {
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault(),
        )
            .format(Date(summary.session.lastModifiedMillis))
    }
    val size = remember(summary.session.sizeBytes) {
        "${summary.session.sizeBytes / 1024} KB"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(weight = 1f),
        )
        if (summary.containsCrash) {
            Text(
                text = stringResource(R.string.logs_session_crash),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val CRASH_MARKER = "E/Crash:"

private data class LogSummary(val session: LogSession, val containsCrash: Boolean)

private fun Context.shareLogText(text: String) {
    val file = File(
        cacheDir,
        "next_player_logs.txt",
    ).apply {
        if (exists()) delete()
        createNewFile()
        writeText(text)
    }
    val uri = FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        clipData = ClipData.newRawUri(
            null,
            uri,
        )
        putExtra(
            Intent.EXTRA_STREAM,
            uri,
        )
    }
    startActivity(
        Intent.createChooser(
            intent,
            getString(R.string.logs_share),
        ),
    )
}

@PreviewLightDark
@Composable
private fun LogViewerScreenPreview() {
    NextPlayerTheme {
        LogViewerScreen(onNavigateUp = {})
    }
}
