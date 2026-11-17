package com.nplayer.core.common

import android.content.Context
import java.io.File

/** A single persisted log session. */
data class LogSession(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** Reads and deletes the log sessions [Logger] keeps on disk. */
class LogStore(private val directory: File) {

    /** The retained sessions, newest first. */
    fun sessions(): List<LogSession> =
        directory.listFiles { file -> file.name.endsWith(LOG_FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                LogSession(
                    file = file,
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                )
            }
            .orEmpty()

    fun read(session: LogSession): String = runCatching { session.file.readText() }.getOrDefault("")

    fun delete(session: LogSession) {
        session.file.delete()
    }

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
