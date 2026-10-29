package com.riffle.app.feature.reader

import kotlin.math.floor
import kotlin.math.roundToInt

// Max dp the viewport alignment may sacrifice to find a better column-snap pitch. See [alignedReaderWidthDp].
internal const val GUTTER_BUDGET_DP = 20

// Returns a reader width (dp) <= availDp that maximises frac(physPx / webViewDpr), where
// physPx = round(c * composeDensity). Readium snaps scrollLeft to multiples of b = physPx/webViewDpr;
// maximising frac(b) minimises per-page left-margin bleed. A perfect integer b (frac=0) is returned
// immediately. On Samsung foldables webViewDpr != composeDensity; pass the display's physical density
// from view.display.getMetrics() clamped to composeDensity.
internal fun alignedReaderWidthDp(
    availDp: Float,
    composeDensity: Float,
    webViewDpr: Float = composeDensity,
): Float {
    if (!availDp.isFinite() || availDp <= 0f || composeDensity <= 0f || webViewDpr <= 0f) return availDp
    val top = floor(availDp).toInt()
    if (top <= 0) return availDp
    val low = maxOf(1, top - GUTTER_BUDGET_DP)
    var best = top
    var bestFrac = -1f
    var c = top
    while (c >= low) {
        val physPx = (c * composeDensity).roundToInt().toFloat()
        val b = physPx / webViewDpr
        val frac = b - floor(b)
        if (frac < 1e-6f) return c.toFloat()   // perfect: b is integer, zero drift
        if (frac > bestFrac + 1e-6f) {
            bestFrac = frac
            best = c
        }
        c--
    }
    return best.toFloat()
}
