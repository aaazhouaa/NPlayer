package com.nplayer.core.common

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Elapsed milliseconds since [startNanos], for log entries that report how long an operation
 * took.
 */
fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/**
 * Application log that mirrors [Log] into rolling session files, so logs outlive the logcat
 * buffer and can be read from the log viewer.
 *
 * Entries go to logcat immediately and are queued for the session file, which a dedicated thread
 * drains in batches: a single flush per entry used to cost a write system call per log line on
 * whichever thread logged it, and most callers are the main thread. Only the [MAX_LOG_SESSIONS]
 * most recent sessions are kept, each capped at [MAX_LOG_SESSION_BYTES], and debug entries are
 * only recorded when verbose logging is on.
 *
 * A crash is written by [GlobalExceptionHandler][com.nplayer.crash] followed by [flush], so the
 * queue never costs the entry that explains the crash.
 *
 * [LogStore] deletes sessions, including the one this object is writing to. Deleting a file that is
 * still open leaves the writer appending to an unlinked inode: every later entry returns normally
 * and none of them is visible in the directory. [onSessionDeleted] is what [LogStore] calls to
 * replace that session instead of deleting it.
 */
object Logger : ActiveLogSession {

    private const val DEBUG = 'D'
    private const val INFO = 'I'
    private const val WARNING = 'W'
    private const val ERROR = 'E'
    private const val TAG = "Logger"

    /** How long an entry may sit in the queue before it is written. */
    private const val FLUSH_DELAY_MS = 250L

    /** Hard cap on the queue, reached only if the writer falls behind the producers. */
    private const val MAX_PENDING_ENTRIES = 2_000

    private const val TRUNCATION_MARKER =
        "[earlier entries dropped: this session reached its size limit]"

    private val lock = Any()

    /** Timestamp formatters are %SimpleDateFormat% and therefore only used while [lock] is held. */
    private val entryTimestampFormat = SimpleDateFormat(
        LOG_ENTRY_TIMESTAMP_PATTERN,
        Locale.US,
    )

    /** Lines waiting for the writer thread. Guarded by [lock]. */
    private val pending = ArrayList<String>(64)

    private var writer: BufferedWriter? = null
    private var sessionFile: File? = null
    private var sessionDirectory: File? = null
    private var sessionBytes = 0L
    private var flushScheduled = false

    private val writerThread: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                runnable,
                "logger-writer",
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    /**
     * Whether debug entries are recorded in the session file. They always reach logcat; keeping
     * them out of the file by default is what makes the file readable without a filter.
     */
    @Volatile
    private var verbose = false

    /**
     * Starts a log session, pruning all but the [MAX_LOG_SESSIONS] most recent sessions. Safe to
     * call again; the previous session is flushed and closed first.
     */
    fun install(context: Context) {
        val directory = File(
            context.filesDir,
            LOG_DIRECTORY_NAME,
        )
        startSession(directory = directory)
        pruneLogSessions(
            directory = directory,
            keep = MAX_LOG_SESSIONS,
        )
        logInfo(
            tag = TAG,
            message = "session started on ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, " +
                "verbose=$verbose)",
        )
    }

    override fun onSessionDeleted(from: File) {
        synchronized(lock) {
            if (sessionFile != from) return
            val directory = sessionDirectory ?: return
            startSession(directory = directory)
        }
        logInfo(
            tag = TAG,
            message = "new session started after the log was cleared",
        )
    }

    /** Closes the open session, if any, and starts a new one in [directory]. */
    private fun startSession(directory: File) {
        synchronized(lock) {
            flushLocked()
            closeWriterLocked()
            directory.mkdirs()
            val file = File(
                directory,
                logSessionFileName(startedAtMillis = System.currentTimeMillis()),
            )
            try {
                writer = file.bufferedWriter().buffered()
            } catch (_: IOException) {
                writer = null
            }
            sessionFile = file
            sessionDirectory = directory
            sessionBytes = runCatching { file.length() }.getOrDefault(0L)
        }
    }

    /** Enables or disables recording debug entries in the session file. */
    fun setVerboseLogging(enabled: Boolean) {
        if (verbose == enabled) return
        verbose = enabled
        logInfo(
            tag = TAG,
            message = "verbose logging ${if (enabled) "enabled" else "disabled"}",
        )
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

    /**
     * Writes every queued entry before returning. The crash handler calls this because the process
     * dies right after it; nothing else needs to, since the queue drains on its own.
     */
    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun log(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val priority = when (level) {
            DEBUG -> Log.DEBUG
            INFO -> Log.INFO
            WARNING -> Log.WARN
            else -> Log.ERROR
        }
        val safeMessage = redactLogText(text = message)
        val safeStack = throwable?.stackTraceToString()?.let { stack ->
            redactLogText(text = stack)
        }
        Log.println(
            priority,
            "Logger - $tag",
            safeMessage + safeStack?.let { stack -> "\n$stack" }.orEmpty(),
        )
        if (level == DEBUG && !verbose) return
        synchronized(lock) {
            if (writer == null) return
            // A writer that falls behind would otherwise grow the queue without bound, and the
            // oldest queued lines are the least useful ones to keep.
            if (pending.size >= MAX_PENDING_ENTRIES) pending.removeAt(index = 0)
            pending.add(
                buildString {
                    append(entryTimestampFormat.format(Date()))
                    append(' ').append(level).append('/').append(tag)
                    append(": ").append(safeMessage)
                    safeStack?.let { stack -> append('\n').append(stack) }
                    append('\n')
                },
            )
            scheduleFlushLocked()
        }
    }

    private fun scheduleFlushLocked() {
        if (flushScheduled) return
        flushScheduled = true
        runCatching {
            writerThread.schedule(
                { drainQueue() },
                FLUSH_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure { flushScheduled = false }
    }

    private fun drainQueue() {
        synchronized(lock) {
            flushScheduled = false
            flushLocked()
        }
    }

    /**
     * Appends the queued lines to the session file. A failed write drops the queue and the writer:
     * logcat keeps every entry, so paying for a failed write on each entry buys nothing.
     */
    private fun flushLocked() {
        if (pending.isEmpty()) return
        val sessionWriter = writer ?: run {
            pending.clear()
            return
        }
        val text = pending.joinToString(separator = "")
        pending.clear()
        try {
            sessionWriter.append(text)
            sessionWriter.flush()
            sessionBytes += text.toByteArray().size
        } catch (_: IOException) {
            closeWriterLocked()
            return
        }
        if (sessionBytes > MAX_LOG_SESSION_BYTES) trimSessionLocked()
    }

    /**
     * Keeps the newest half of an oversized session. The rewrite itself lives in [trimLogFile];
     * this only reopens the writer afterwards and records the new length.
     */
    private fun trimSessionLocked() {
        val file = sessionFile ?: return
        closeWriterLocked()
        val trimmed = trimLogFile(
            file = file,
            keepBytes = MAX_LOG_SESSION_BYTES / 2,
            marker = TRUNCATION_MARKER,
        )
        if (trimmed == null) {
            // The file is in an unknown state; keep logcat as the only sink for this session.
            sessionBytes = 0L
            return
        }
        sessionBytes = trimmed
        writer = runCatching {
            FileOutputStream(
                file,
                true,
            ).bufferedWriter().buffered()
        }
            .getOrNull()
    }

    private fun closeWriterLocked() {
        try {
            writer?.close()
        } catch (_: IOException) {
            // The session file is unusable either way; logcat still has every entry.
        }
        writer = null
    }
}
