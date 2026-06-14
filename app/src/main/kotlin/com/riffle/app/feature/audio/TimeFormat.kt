package com.riffle.app.feature.audio

/** mm:ss under an hour, h:mm:ss otherwise. */
internal fun formatHms(totalSec: Double): String {
    val s = totalSec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
