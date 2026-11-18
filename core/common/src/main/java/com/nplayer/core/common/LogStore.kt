package com.nplayer.core.common

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * A session file of the application log.
 *
 * @property startedAtMillis when the process started logging, which is what the list shows.
 * @property sizeBytes the size of the session file on disk.
 * @property lastModifiedMillis the last write, kept for diagnostics such as a still-running session.
 */
data class LogSession(
    val file: File,
    val name: String,
    val startedAtMillis: Long,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val containsCrash: Boolean,
)

/** Reads and deletes the log sessions [Logger] keeps on disk. */
class LogStore(private val directory: File) {

    /** The retained sessions, newest first. */
    fun sessions(): List<LogSession> = logSessionFiles(directory = directory).map { file ->
        LogSession(
            file = file,
            name = file.name,
            startedAtMillis = logSessionStartedAtMillis(file = file),
            sizeBytes = file.length(),
            lastModifiedMillis = file.lastModified(),
            containsCrash = readTail(
                file = file,
                maxBytes = LOG_CRASH_SCAN_BYTES,
            ).contains(other = LOG_CRASH_MARKER),
        )
    }

    fun read(session: LogSession): String = runCatching { session.file.readText() }.getOrDefault("")

    fun delete(session: LogSession) {
        session.file.delete()
    }

    /** Deletes every retained session, so the viewer starts empty. */
    fun deleteAll() {
        logSessionFiles(directory = directory).forEach { file -> file.delete() }
    }

    /**
     * Reads at most the last [maxBytes] of [file]. A session is capped at [MAX_LOG_SESSION_BYTES],
     * so scanning all of them for a crash marker on every list render would read several megabytes
     * per session.
     */
    private fun readTail(file: File, maxBytes: Int): String = runCatching {
        RandomAccessFile(
            file,
            "r",
        ).use { reader ->
            val length = reader.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            reader.seek(start)
            ByteArray(size = (length - start).toInt()).also { bytes ->
                reader.readFully(bytes)
            }
        }.decodeToString()
    }.getOrDefault("")

    companion object {
        fun create(context: Context): LogStore {
            val directory = File(
                context.filesDir,
                LOG_DIRECTORY_NAME,
            )
            return LogStore(directory = directory)
        }
    }
}
