package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LOG_DIRECTORY_NAME = "logs"
internal const val LOG_FILE_PREFIX = "log-"
internal const val LOG_FILE_SUFFIX = ".log"
internal const val MAX_LOG_SESSIONS = 3

/**
 * Elapsed milliseconds since [startNanos], for log entries that report how long an operation
 * took.
 */
fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/**
 * Application log that mirrors [Log] into rolling session files, so logs outlive the logcat
 * buffer and can be read from the log viewer. Only the [MAX_LOG_SESSIONS] most recent sessions
 * are kept on disk; every entry is flushed as it is written so a crash is captured as well.
 */
object Logger {

    private const val DEBUG = 'D'
    private const val INFO = 'I'
    private const val WARNING = 'W'
    private const val ERROR = 'E'
    private const val TAG = "Logger"

    private val lock = Any()
    private val entryTimestampFormat = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS",
        Locale.US,
    )
    private val sessionNameFormat = SimpleDateFormat(
        "yyyyMMdd-HHmmss",
        Locale.US,
    )

    private var writer: BufferedWriter? = null

    /** Starts a log session, pruning all but the [MAX_LOG_SESSIONS] most recent sessions. */
    fun install(context: Context) {
        synchronized(lock) {
            closeWriter()
            val directory = File(
                context.filesDir,
                LOG_DIRECTORY_NAME,
            ).apply { mkdirs() }
            val sessionFile = File(
                directory,
                LOG_FILE_PREFIX + sessionNameFormat.format(Date()) + LOG_FILE_SUFFIX,
            )
            writer = sessionFile.bufferedWriter().buffered()
            pruneSessions(directory = directory)
            logInfo(
                tag = TAG,
                message = "session started on ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
            )
        }
    }

    fun logDebug(tag: String, message: String) {
        log(
            level = DEBUG,
            tag = tag,
            message = message,
        )
    }

    fun logInfo(tag: String, message: String) {
        log(
            level = INFO,
            tag = tag,
            message = message,
        )
    }

    fun logWarning(tag: String, message: String, throwable: Throwable? = null) {
        log(
            level = WARNING,
            tag = tag,
            message = message,
            throwable = throwable,
        )
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        log(
            level = ERROR,
            tag = tag,
            message = message,
            throwable = throwable,
        )
    }

    private fun log(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val priority = when (level) {
            DEBUG -> Log.DEBUG
            INFO -> Log.INFO
            WARNING -> Log.WARN
            else -> Log.ERROR
        }
        val logcatMessage = message + throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        Log.println(
            priority,
            "Logger - $tag",
            logcatMessage,
        )
        synchronized(lock) {
            appendToSession(
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
            )
        }
    }

    private fun appendToSession(level: Char, tag: String, message: String, throwable: Throwable?) {
        val sessionWriter = writer ?: return
        try {
            sessionWriter.append(entryTimestampFormat.format(Date()))
            sessionWriter.append(' ').append(level).append('/').append(tag)
            sessionWriter.append(": ").append(message)
            throwable?.let { sessionWriter.append('\n').append(it.stackTraceToString()) }
            sessionWriter.append('\n')
            sessionWriter.flush()
        } catch (_: IOException) {
            // Keep logcat as the only sink rather than paying for a failed write on every entry.
            closeWriter()
        }
    }

    private fun pruneSessions(directory: File) {
        val sessions = directory.listFiles { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        } ?: return
        sessions.sortedByDescending { it.name }
            .drop(n = MAX_LOG_SESSIONS)
            .forEach { session -> session.delete() }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: IOException) {
            // The session file is unusable either way; logcat still has every entry.
        }
        writer = null
    }
}
