package com.riffle.feature.reader

import kotlin.math.roundToInt

// ARGB → CSS colour conversion for highlight decorations. Shared (issue #1066) because both
// platforms paint decorations from the same persisted `HighlightColor.argb` token and must
// produce byte-identical CSS — a divergence here shows up as a highlight rendering in a
// different shade on one platform.

/**
 * Format [value] with exactly two decimal places, independent of locale.
 *
 * Replaces the JVM-only `"%.2f".format(Locale.US, …)`: the explicit `Locale.US` existed purely to
 * stop a comma decimal separator reaching CSS, which this cannot produce at all.
 *
 * Uses [roundToInt] (ties toward positive infinity), which matches `Formatter`'s HALF_UP over the
 * non-negative domain this is called with — note `kotlin.math.round` would NOT, being ties-to-even.
 * The input is coerced into 0.0–1.0 because an alpha outside that range is meaningless in CSS and
 * a negative one would otherwise render as the malformed `"0.-50"`.
 */
private fun twoDecimals(value: Double): String {
    val hundredths = (value.coerceIn(0.0, 1.0) * 100).roundToInt()
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
