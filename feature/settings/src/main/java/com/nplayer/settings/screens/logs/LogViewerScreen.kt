package com.nplayer.settings.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.nplayer.core.common.LogEntry
import com.nplayer.core.common.LogLevel
import com.nplayer.core.common.LogSession
import com.nplayer.core.common.LogStore
import com.nplayer.core.common.copyLogReportToClipboard
import com.nplayer.core.common.filterByLevels
import com.nplayer.core.common.filterByQuery
import com.nplayer.core.common.parseLogEntries
import com.nplayer.core.ui.R
import com.nplayer.core.ui.components.CancelButton
import com.nplayer.core.ui.components.NextDialog
import com.nplayer.core.ui.components.NextOutlinedTextField
import com.nplayer.core.ui.components.NextTopAppBar
import com.nplayer.core.ui.designsystem.NextIcons
import com.nplayer.core.ui.extensions.copy
import com.nplayer.core.ui.theme.NPlayerTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the retained log sessions, newest first. A session opens in its own dialog, where entries
 * can be filtered by level, searched and scrolled a page at a time. Crashes are written into the
 * same sessions, so there is no separate crash dialog.
 */
@Composable
fun LogViewerScreen(onNavigateUp: () -> Unit) {
    val context = LocalContext.current
    val logStore = remember(context) { LogStore.create(context) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<LogSession?>(null) }
    var dialog by remember { mutableStateOf(value = null as LogViewerDialog?) }
    var refreshKey by remember { mutableStateOf(value = 0) }
    val sessions by produceState(
        initialValue = emptyList<LogSession>(),
        key1 = refreshKey,
    ) {
        value = withContext(Dispatchers.IO) { logStore.sessions() }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.logs_title),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = sessions.isNotEmpty(),
                        onClick = { dialog = LogViewerDialog.DeleteAll },
                    ) {
                        Icon(
                            imageVector = NextIcons.DeleteSweep,
                            contentDescription = stringResource(R.string.logs_delete_all),
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
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(all = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(weight = 1f)) {
                    items(
                        items = sessions,
                        key = { it.name },
                    ) { session ->
                        LogSessionItem(
                            session = session,
                            onClick = { selected = session },
                        )
                    }
                }
            }
        }
    }

    selected?.let { session ->
        LogSessionDialog(
            logStore = logStore,
            session = session,
            onRequestDelete = {
                // The confirmation replaces the session view: canceling it returns to the list
                // rather than stacking dialogs.
                selected = null
                dialog = LogViewerDialog.DeleteSession(session = session)
            },
            onDismiss = { selected = null },
        )
    }

    when (val current = dialog) {
        null -> Unit

        LogViewerDialog.DeleteAll -> NextDialog(
            onDismissRequest = { dialog = null },
            title = { Text(text = stringResource(R.string.logs_delete_all)) },
            content = { Text(text = stringResource(R.string.logs_delete_all_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        scope.launch {
                            withContext(Dispatchers.IO) { logStore.deleteAll() }
                            selected = null
                            refreshKey++
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = { CancelButton(onClick = { dialog = null }) },
        )

        is LogViewerDialog.DeleteSession -> NextDialog(
            onDismissRequest = { dialog = null },
            title = { Text(text = stringResource(R.string.logs_delete)) },
            content = { Text(text = stringResource(R.string.logs_delete_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        val session = current.session
                        scope.launch {
                            withContext(Dispatchers.IO) { logStore.delete(session = session) }
                            refreshKey++
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = { CancelButton(onClick = { dialog = null }) },
        )
    }
}

@Composable
private fun LogSessionDialog(
    logStore: LogStore,
    session: LogSession,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val entries by produceState(
        initialValue = null as List<LogEntry>?,
        key1 = session,
    ) {
        value = withContext(Dispatchers.IO) { parseLogEntries(text = logStore.read(session)) }
    }
    var selectedLevels by remember(session.name) {
        mutableStateOf(value = LogLevel.entries.toSet())
    }
    var query by rememberSaveable(session.name) { mutableStateOf(value = "") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = DIALOG_MAX_WIDTH)
                .padding(all = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(all = 16.dp).heightIn(
                    max = LocalConfiguration.current.screenHeightDp.dp * DIALOG_HEIGHT_FRACTION,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTimestamp(millis = session.startedAtMillis),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(weight = 1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = NextIcons.Close,
                            contentDescription = stringResource(R.string.logs_close),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LogLevelFilter(
                    selectedLevels = selectedLevels,
                    onLevelClick = { level ->
                        selectedLevels = if (level in selectedLevels) {
                            selectedLevels - level
                        } else {
                            selectedLevels + level
                        }
                    },
                )
                NextOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text(text = stringResource(R.string.logs_search)) },
                    trailingIcon = {
                        Icon(
                            imageVector = NextIcons.Search,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                )
                val content = entries
                val visibleEntries = content
                    ?.filterByLevels(levels = selectedLevels)
                    ?.filterByQuery(query = query)
                    .orEmpty()
                val searched = query.isNotBlank()
                val emptyText = if (searched) {
                    R.string.logs_search_empty
                } else {
                    R.string.logs_levels_empty
                }
                when {
                    content == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                    visibleEntries.isEmpty() -> Text(
                        text = stringResource(emptyText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    else -> LogEntryList(
                        entries = visibleEntries,
                        modifier = Modifier.weight(weight = 1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = content != null,
                        onClick = {
                            copyLogReportToClipboard(
                                context.getSystemService(ClipboardManager::class.java),
                                visibleEntries.toLogText(),
                            )
                            Toast.makeText(
                                context,
                                R.string.logs_copied,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Icon(
                            imageVector = NextIcons.Copy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.logs_copy),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    TextButton(onClick = { context.shareLogText(visibleEntries.toLogText()) }) {
                        Text(text = stringResource(R.string.logs_share))
                    }
                    TextButton(onClick = onRequestDelete) {
                        Text(text = stringResource(R.string.logs_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLevelFilter(selectedLevels: Set<LogLevel>, onLevelClick: (LogLevel) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogLevel.entries.forEach { level ->
            FilterChip(
                selected = level in selectedLevels,
                onClick = { onLevelClick(level) },
                label = { Text(text = stringResource(level.labelResId)) },
            )
        }
    }
}

/**
 * Renders the filtered entries, scrolling a screenful at a time on remote/keyboard input while
 * keeping drag scrolling for touch.
 */
@Composable
private fun LogEntryList(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(key1 = Unit) { runCatching { focusRequester.requestFocus() } }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionDown, Key.PageDown -> 1
                    Key.DirectionUp, Key.PageUp -> -1
                    else -> 0
                }
                // Let focus leave the list once there is nothing left to scroll that way.
                val canScroll = if (direction > 0) {
                    listState.canScrollForward
                } else {
                    direction < 0 && listState.canScrollBackward
                }
                if (!canScroll) return@onPreviewKeyEvent false
                val amount =
                    (listState.layoutInfo.viewportSize.height * PAGE_FRACTION) * direction
                scope.launch { listState.animateScrollBy(value = amount) }
                true
            },
    ) {
        // The index is stable because the entry list is replaced wholesale when a filter changes.
        itemsIndexed(
            items = entries,
            key = { index, _ -> index },
        ) { _, entry ->
            SelectionContainer {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = entry.level?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LogLevel.color() = when (this) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}

private val LogLevel.labelResId: Int
    get() = when (this) {
        LogLevel.DEBUG -> R.string.logs_level_debug
        LogLevel.INFO -> R.string.logs_level_info
        LogLevel.WARNING -> R.string.logs_level_warning
        LogLevel.ERROR -> R.string.logs_level_error
    }

@Composable
private fun LogSessionItem(session: LogSession, onClick: () -> Unit) {
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
            text = formatTimestamp(millis = session.startedAtMillis),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(weight = 1f),
        )
        if (session.containsCrash) {
            Text(
                text = stringResource(R.string.logs_session_crash),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = formatLogSize(bytes = session.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PAGE_FRACTION = 0.8f
private const val DIALOG_HEIGHT_FRACTION = 0.85f
private const val LOG_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
private const val LOG_SHARE_FILE_NAME = "n-player-logs.txt"
private val DIALOG_MAX_WIDTH = 720.dp

private sealed interface LogViewerDialog {
    data object DeleteAll : LogViewerDialog

    data class DeleteSession(val session: LogSession) : LogViewerDialog
}

private fun formatTimestamp(millis: Long): String = SimpleDateFormat(
    LOG_TIMESTAMP_PATTERN,
    Locale.getDefault(),
).format(Date(millis))

/** Sizes are shown in the unit that reads as a size, not as a long run of kilobytes. */
internal fun formatLogSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(
        Locale.getDefault(),
        "%.1fMB",
        bytes / 1048576.0,
    )

    else -> "${bytes / 1024}KB"
}

private fun List<LogEntry>.toLogText(): String = joinToString(separator = "\n") { it.text }

private fun Context.shareLogText(text: String) {
    val file = File(
        cacheDir,
        LOG_SHARE_FILE_NAME,
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
    NPlayerTheme {
        LogViewerScreen(onNavigateUp = {})
    }
}
