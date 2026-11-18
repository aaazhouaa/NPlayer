package com.nplayer.crash

import com.nplayer.core.common.CRASH_LOG_TAG
import com.nplayer.core.common.Logger
import kotlin.system.exitProcess

/**
 * Writes the uncaught exception to the current log session before the platform handler kills the
 * process, so it can be read from the log viewer afterwards. Native crashes (SIGSEGV/SIGABRT in
 * the media stack) never reach this handler; those still need `adb logcat`.
 *
 * The log queue is drained synchronously here: the process is about to die, and a queued entry
 * would be lost.
 */
class GlobalExceptionHandler : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        Logger.logError(
            tag = CRASH_LOG_TAG,
            message = "uncaught exception on thread \"${t.name}\"",
            throwable = e,
        )
        Logger.flush()
        defaultHandler?.uncaughtException(
            t,
            e,
        ) ?: exitProcess(status = 2)
    }
}
