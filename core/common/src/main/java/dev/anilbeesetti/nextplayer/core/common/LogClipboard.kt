package dev.anilbeesetti.nextplayer.core.common

import android.content.ClipData
import android.content.ClipboardManager

internal const val MAX_CLIPBOARD_REPORT_CHARS = 100_000
internal const val CLIPBOARD_REPORT_TRUNCATION_MARKER =
    "\n\n[Log report truncated. Use Share for full logs.]"

fun logReportForClipboard(report: String): String {
    if (report.length <= MAX_CLIPBOARD_REPORT_CHARS) return report

    val prefixLength = MAX_CLIPBOARD_REPORT_CHARS - CLIPBOARD_REPORT_TRUNCATION_MARKER.length
    return report.take(n = prefixLength) + CLIPBOARD_REPORT_TRUNCATION_MARKER
}

fun copyLogReportToClipboard(clipboard: ClipboardManager, report: String) {
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            null,
            logReportForClipboard(report = report),
        ),
    )
}
