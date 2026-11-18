package com.nplayer.core.common

/** Severity [Logger] records for a single entry. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

/**
 * One entry of a [Logger] session file. [text] keeps the timestamped line plus any continuation
 * lines that follow it, such as a stack trace.
 */
data class LogEntry(val level: LogLevel?, val text: String)

/**
 * Splits a session file into entries, classifying each one by the severity [Logger] wrote. Lines
 * without a timestamped level prefix (stack traces, or the head of a truncated file) stay attached
 * to the entry before them, and keep a `null` level when they lead the file.
 */
fun parseLogEntries(text: String): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()
    val current = StringBuilder()
    var currentLevel: LogLevel? = null

    fun flush() {
        if (current.isNotEmpty()) {
            entries.add(
                LogEntry(
                    level = currentLevel,
                    text = current.toString(),
                ),
            )
            current.clear()
        }
    }

    text.lineSequence().forEach { line ->
        val level = entryLevelOf(line = line)
        if (level != null) {
            flush()
            currentLevel = level
            current.append(line)
            return@forEach
        }
        if (current.isEmpty() && line.isBlank()) return@forEach
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
    }
    flush()
    return entries
}

/**
 * Keeps the entries whose level is selected. Entries without a level carry lines that could not be
 * classified, so they are always kept rather than silently dropped.
 */
fun List<LogEntry>.filterByLevels(levels: Set<LogLevel>): List<LogEntry> =
    filter { entry -> entry.level == null || entry.level in levels }

/**
 * Keeps the entries that mention [query], case-insensitively. A blank query keeps everything, so
 * the viewer can run this unconditionally next to [filterByLevels].
 */
fun List<LogEntry>.filterByQuery(query: String): List<LogEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { entry ->
        entry.text.contains(
            other = trimmed,
            ignoreCase = true,
        )
    }
}

// Matches "yyyy-MM-dd HH:mm:ss.SSS L/Tag: " as written by Logger.
private val ENTRY_PREFIX = Regex(
    pattern = """^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ([DIWE])/""",
)

private fun entryLevelOf(line: String): LogLevel? =
    when (ENTRY_PREFIX.find(input = line)?.groupValues?.get(1)) {
        "D" -> LogLevel.DEBUG
        "I" -> LogLevel.INFO
        "W" -> LogLevel.WARNING
        "E" -> LogLevel.ERROR
        else -> null
    }
