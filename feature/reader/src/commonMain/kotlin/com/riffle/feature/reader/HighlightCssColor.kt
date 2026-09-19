package com.riffle.feature.reader

import kotlin.math.round

// ARGB → CSS colour conversion for highlight decorations. Shared (issue #1066) because both
// platforms paint decorations from the same persisted `HighlightColor.argb` token and must
// produce byte-identical CSS — a divergence here shows up as a highlight rendering in a
// different shade on one platform.

/**
 * Format [value] (0.0–1.0) with exactly two decimal places, independent of locale.
 *
 * Replaces the JVM-only `"%.2f".format(Locale.US, …)`: the explicit `Locale.US` existed purely to
 * stop a comma decimal separator reaching CSS, which this cannot produce at all. Rounding is
 * half-away-from-zero, matching `Formatter`'s HALF_UP for the non-negative inputs used here.
 */
private fun twoDecimals(value: Double): String {
    val hundredths = round(value * 100).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/** Convert an ARGB color int to a CSS rgba() string for injection into WebView JS. */
fun Int.toCssRgba(): String {
    val a = (this ushr 24 and 0xFF) / 255.0
    val r = this ushr 16 and 0xFF
    val g = this ushr 8 and 0xFF
    val b = this and 0xFF
    return "rgba($r,$g,$b,${twoDecimals(a)})"
}

/** Convert an ARGB color int to a CSS rgba() string, overriding the alpha with [alpha] (0.0–1.0). */
fun Int.toCssRgbaWithAlpha(alpha: Double): String {
    val r = this ushr 16 and 0xFF
    val g = this ushr 8 and 0xFF
    val b = this and 0xFF
    return "rgba($r,$g,$b,${twoDecimals(alpha)})"
}
