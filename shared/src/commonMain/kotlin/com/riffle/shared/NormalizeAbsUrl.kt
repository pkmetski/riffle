package com.riffle.shared

/** Prefixes https:// when the user omits a scheme; existing http(s) schemes are preserved. */
internal fun normalizeAbsUrl(raw: String): String {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
