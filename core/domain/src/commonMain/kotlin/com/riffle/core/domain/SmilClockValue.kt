package com.riffle.core.domain

/**
 * SMIL clock values come in several shapes:
 *   - full clock: `1:02:03.500` (h:mm:ss) or `02:03.5` (mm:ss)
 *   - timecount with unit: `12.5s`, `300ms`, `1.5min`, `2h`
 *   - bare seconds: `12.5`
 *
 * Returns 0.0 for blank/unparseable input — a missing clip bound degrades to "no offset" rather
 * than crashing playback.
 *
 * Shared by the JVM DOM-based [SmilOverlayParser] and the iOS ksoup-based one so the two can
 * never disagree on a clip's timing (a drift here would desynchronise the readaloud highlight).
 */
fun parseSmilClockValue(raw: String): Double {
    val v = raw.trim()
    if (v.isEmpty()) return 0.0

    if (v.contains(':')) {
        val parts = v.split(':')
        var seconds = 0.0
        for (part in parts) {
            seconds = seconds * 60.0 + (part.toDoubleOrNull() ?: return 0.0)
        }
        return seconds
    }

    return when {
        v.endsWith("ms") -> v.dropLast(2).toDoubleOrNull()?.div(1000.0) ?: 0.0
        v.endsWith("min") -> v.dropLast(3).toDoubleOrNull()?.times(60.0) ?: 0.0
        v.endsWith("h") -> v.dropLast(1).toDoubleOrNull()?.times(3600.0) ?: 0.0
        v.endsWith("s") -> v.dropLast(1).toDoubleOrNull() ?: 0.0
        else -> v.toDoubleOrNull() ?: 0.0
    }
}
