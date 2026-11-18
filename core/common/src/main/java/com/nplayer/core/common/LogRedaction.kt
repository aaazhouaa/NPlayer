package com.nplayer.core.common

/**
 * Credentials must never reach the log file or logcat: entries are copied and shared from the log
 * viewer, and a network URI can carry a password (`smb://user:pass@host/share/clip.mkv`). Both
 * redaction passes run on the text before it is written anywhere.
 */
fun redactLogText(text: String): String =
    URI_USER_INFO.replace(input = text) { match -> redactUserInfo(match = match) }
        .replace(
            regex = SENSITIVE_PARAMETER,
            replacement = "\$1***",
        )

// The user name is kept: it makes a failing share diagnosable, while the password does not.
private fun redactUserInfo(match: MatchResult): CharSequence {
    val scheme = match.groupValues[1]
    val user = match.groupValues[2]
    return if (user.isEmpty()) "$scheme***@" else "$scheme$user@"
}

// scheme://user[:password]@
private val URI_USER_INFO = Regex(
    pattern = """([a-zA-Z][a-zA-Z0-9+.\-]*://)([^/\s@:]*)(?::[^/\s@]*)?@""",
)

// Query parameters whose value must not be readable, matched on the parameter name.
private val SENSITIVE_PARAMETER = Regex(
    pattern = """([?&](?:pass|password|passwd|pwd|token|secret|sig|signature|apikey|api_key|""" +
        """access_token|auth)=)[^&\s]*""",
    option = RegexOption.IGNORE_CASE,
)
