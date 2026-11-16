package dev.anilbeesetti.nextplayer.crash

import dev.anilbeesetti.nextplayer.core.common.Logger
import kotlin.system.exitProcess

/**
 * Writes the uncaught exception to the current log session before the platform handler kills the
 * process, so it can be read from the log viewer afterwards. Native crashes (SIGSEGV/SIGABRT in
 * the media stack) never reach this handler; those still need `adb logcat`.
 */
class GlobalExceptionHandler : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        Logger.logError(
            tag = TAG,
            message = "uncaught exception on thread \"${t.name}\"",
            throwable = e,
        )
        defaultHandler?.uncaughtException(
            t,
            e,
        ) ?: exitProcess(status = 2)
    }

    private companion object {
        const val TAG = "Crash"
    }
}
