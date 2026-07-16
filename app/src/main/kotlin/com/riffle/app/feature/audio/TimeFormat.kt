package com.riffle.app.feature.audio

/** mm:ss under an hour, h:mm:ss otherwise. */
internal fun formatHms(totalSec: Double): String {
    val s = totalSec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Human-readable remaining-time label for the system media notification / lock-screen player —
 * e.g. "3h 12m left", "45m left", "Less than a minute left". Whole-minute granularity so we
 * update the [androidx.media3.common.MediaMetadata] at most once per minute.
 */
fun formatRemainingReadable(remainingSec: Double): String {
    val totalMinutes = (remainingSec / 60.0).toLong().coerceAtLeast(0)
    if (totalMinutes == 0L) return "Less than a minute left"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m left" else "${m}m left"
}
