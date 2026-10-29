package com.riffle.app.feature.reader

import kotlin.math.floor
import kotlin.math.min

// How much width (dp) the viewport alignment is allowed to give up. See [alignedReaderWidthDp].
internal const val GUTTER_BUDGET_DP = 8

// Reader width (dp) <= availDp for single-page paginated reflowable. The ideal is a width whose
// physical pixel count (width * density) is a whole number: then Readium's column-snap pitch
// b = physicalWidth/dpr equals CSS innerWidth and the column grid stops drifting (the
// right-margin-vanishes bug; see reference_reader_right_margin_is_column_snap_bug).
//
// The catch — why this misbehaved on physical hardware but never on the emulator: density is
// densityDpi/160, so in lowest terms its denominator divides 160. Standard phone density BUCKETS
// (2.625 = 21/8, 2.75 = 11/4, integers) have small denominators, so a whole-pixel width sits within
// a few dp of availDp. But a densityDpi coprime to 160 — common on real devices, and especially
// after the user changes "Display size" in system settings — makes whole-pixel widths up to 160dp
// apart. Insisting on an exact one then shaved 100+dp off the page, i.e. large, asymmetric margins
// in paginated mode only (the symptom this revision fixes).
//
// Fix: never give up more than [GUTTER_BUDGET_DP]. Within that budget pick the width with the
// smallest snap error frac(width * density) — an exact whole-pixel width when one exists in range
// (zero drift; identical to the old behaviour on every real density bucket and integer density),
// otherwise the closest partial alignment. The residual error is sub-pixel per page and stays
// imperceptible across a whole chapter.
//
// Direction matters: when frac(c * density) < 0.5, Compose rounds W_px DOWN, so
// b = W_px/dpr < window.innerWidth. Readium's column snap then undershoots the column boundary
// and the previous page bleeds in from the left (the Flip8 cover-screen regression). When
// frac >= 0.5, Compose rounds W_px UP → b >= innerWidth → snap lands at or past the column
// boundary (any overflow is imperceptible next-page bleed on the right, not the previous page
// on the left). So among candidates with no perfect whole-pixel solution, prefer frac >= 0.5
// (round-up); fall back to the globally smallest error only when no round-up exists in budget.
// Larger widths still win ties, keeping the gutter minimal.
internal fun alignedReaderWidthDp(availDp: Float, density: Float): Float {
    if (!availDp.isFinite() || availDp <= 0f || density <= 0f) return availDp
    val top = floor(availDp).toInt()
    if (top <= 0) return availDp
    val low = maxOf(1, top - GUTTER_BUDGET_DP)
    var best = top
    var bestErr = Float.MAX_VALUE
    var bestRoundUp = -1        // best c where frac >= 0.5 (W_px rounds up, b >= innerWidth)
    var bestRoundUpErr = Float.MAX_VALUE
    var c = top
    while (c >= low) {
        val px = c * density
        val frac = px - floor(px)
        val err = min(frac, 1f - frac)
        if (err < bestErr - 1e-6f) {
            bestErr = err
            best = c
        }
        if (frac >= 0.5f - 1e-6f) {
            val roundUpErr = 1f - frac
            if (roundUpErr < bestRoundUpErr - 1e-6f) {
                bestRoundUpErr = roundUpErr
                bestRoundUp = c
            }
        }
        c--
    }
    return when {
        bestErr < 1e-6f -> best.toFloat()   // perfect whole-pixel width: b == innerWidth, no drift
        bestRoundUp >= 0 -> bestRoundUp.toFloat() // round-up: b > innerWidth, no left-margin bleed
        else -> best.toFloat()              // fallback: no round-up in budget
    }
}
