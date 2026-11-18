package com.nplayer.core.common

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LOG_DIRECTORY_NAME = "logs"
internal const val LOG_FILE_PREFIX = "log-"
internal const val LOG_FILE_SUFFIX = ".log"

/** How many session files stay on disk, including the one currently being written. */
internal const val MAX_LOG_SESSIONS = 3

/**
 * A session file is trimmed back to its newest half once it grows past this size. Logging never
 * blocks its caller, so without a cap a long playback or a repeated scan could fill the device.
 */
internal const val MAX_LOG_SESSION_BYTES = 2L * 1024 * 1024

/** How much of a session tail the viewer has to read to find a crash. */
internal const val LOG_CRASH_SCAN_BYTES = 64 * 1024

internal const val LOG_SESSION_NAME_PATTERN = "yyyyMMdd-HHmmss"

/** Length of the timestamp in a session file name, e.g. `20260102-030405`. */
private const val LOG_SESSION_NAME_LENGTH = 15

internal const val LOG_ENTRY_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

/**
 * The uncaught exception handler logs under this tag, and the viewer flags a session that carries
 * it, so both sides read the tag from here instead of repeating the literal.
 */
const val CRASH_LOG_TAG = "Crash"

internal const val LOG_CRASH_MARKER = "E/$CRASH_LOG_TAG:"

/**
 * Matches the file names the logger writes, so the store lists exactly what pruning deletes.
 * Anything else that happens to end in `.log` is ignored by both.
 */
internal fun isLogFileName(name: String): Boolean =
    name.startsWith(prefix = LOG_FILE_PREFIX) && name.endsWith(suffix = LOG_FILE_SUFFIX)

internal fun logSessionFileName(startedAtMillis: Long): String =
    LOG_FILE_PREFIX + logTimestampFormat().format(Date(startedAtMillis)) + LOG_FILE_SUFFIX

/**
 * When the session started, parsed from its file name so the list shows a start rather than the
 * last write. A name that does not parse falls back to the modification time.
 */
internal fun logSessionStartedAtMillis(file: File): Long {
    val encoded = file.name
        .removePrefix(prefix = LOG_FILE_PREFIX)
        .removeSuffix(suffix = LOG_FILE_SUFFIX)
        .take(n = LOG_SESSION_NAME_LENGTH)
    return runCatching { logTimestampFormat().parse(encoded)?.time }.getOrNull()
        ?: file.lastModified()
}

/** Log session files in [directory], newest session first. */
internal fun logSessionFiles(directory: File): List<File> =
    directory.listFiles { file -> file.isFile && isLogFileName(name = file.name) }
        ?.sortedByDescending { file -> logSessionStartedAtMillis(file = file) }
        .orEmpty()

/** Deletes all but the [keep] newest session files. */
internal fun pruneLogSessions(directory: File, keep: Int) {
    logSessionFiles(directory = directory)
        .drop(n = keep)
        .forEach { file -> file.delete() }
}

/**
 * Rewrites [file] to hold its newest [keepBytes] of content, prefixed by [marker]. A session that
 * reached its limit is only useful up to the point where it was trimmed, so the marker makes the
 * gap visible instead of silently dropping the head of the log. The byte cut lands mid-line, so
 * the partial leading line is dropped as well.
 *
 * @return the new length, or null when the file could not be rewritten. A null leaves the caller
 * free to close its writer rather than append to a file of unknown length.
 */
internal fun trimLogFile(file: File, keepBytes: Long, marker: String): Long? = runCatching {
    val start: Long
    val read: String = RandomAccessFile(
        file,
        "r",
    ).use { reader ->
        val length = reader.length()
        start = (length - keepBytes).coerceAtLeast(0L)
        reader.seek(start)
        ByteArray(size = (length - start).toInt()).also { bytes -> reader.readFully(bytes) }
    }.decodeToString()
    // Only a cut that started mid-file leaves a partial line to drop. A previous trim wrote the
    // marker as the first line, so the tail already carries it; adding another would pile up.
    val tail = if (start > 0L) {
        read.substringAfter(
            delimiter = '\n',
            missingDelimiterValue = "",
        )
    } else {
        read
    }
    file.writeText(marker + "\n" + tail.removePrefix(prefix = "$marker\n"))
    file.length()
}.getOrNull()

/**
 * A formatter for [LOG_SESSION_NAME_PATTERN]. Each caller gets its own because [SimpleDateFormat]
 * is not thread safe.
 */
private fun logTimestampFormat(): SimpleDateFormat = SimpleDateFormat(
    LOG_SESSION_NAME_PATTERN,
    Locale.US,
)
