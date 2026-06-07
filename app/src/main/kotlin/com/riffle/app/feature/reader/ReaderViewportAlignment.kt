package com.riffle.app.feature.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
// imperceptible across a whole chapter (worst case ~5px over 40 pages) versus the ~20px asymmetry
// exact alignment was built to remove. Larger widths win ties, keeping the gutter minimal.
internal fun alignedReaderWidthDp(availDp: Float, density: Float): Float {
    if (!availDp.isFinite() || availDp <= 0f || density <= 0f) return availDp
    val top = floor(availDp).toInt()
    if (top <= 0) return availDp
    val low = maxOf(1, top - GUTTER_BUDGET_DP)
    var best = top
    var bestErr = Float.MAX_VALUE
    var c = top
    while (c >= low) {
        val px = c * density
        val err = abs(px - px.roundToInt())
        if (err < bestErr - 1e-6f) { // strictly better only; ties keep the larger (earlier) width
            bestErr = err
            best = c
        }
        c--
    }
    return best.toFloat()
}
