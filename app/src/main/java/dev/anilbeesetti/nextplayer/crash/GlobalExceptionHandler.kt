package dev.anilbeesetti.nextplayer.crash

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/**
 * Shows [CrashActivity] with the stack trace before the platform handler kills the process.
 *
 * Native crashes (SIGSEGV/SIGABRT in the media stack) never reach this handler; those still
 * need `adb logcat`.
 */
class GlobalExceptionHandler(private val context: Context, private val activity: Class<*>) :
    Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        val intent = Intent(
            context,
            activity,
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(
            "exception",
            e.stackTraceToString(),
        )
        context.startActivity(intent)
        defaultHandler?.uncaughtException(
            t,
            e,
        ) ?: exitProcess(status = 0)
    }
}
