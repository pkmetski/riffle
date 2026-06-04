package com.riffle.app.feature.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// Largest reader width (dp) <= availDp whose physical pixel width (width * density) is a whole
// number — so Readium's paginated column-snap pitch b = physicalWidth/dpr equals CSS innerWidth
// and the column grid stops drifting (the right-margin-vanishes bug; see
// reference_reader_right_margin_is_column_snap_bug). On integer densities (e.g. dpr 3.0) every
// integer dp already maps to whole pixels, so this returns floor(availDp) — no gutter cost.
//
// The loop is bounded by the density's denominator: for a density p/q in lowest terms, c*density
// is whole at every multiple of q, so a hit is found within q steps (Android densities are
// small-denominator rationals, e.g. 2.625 = 21/8). If none is found the floored width is returned
// — never the raw fractional availDp, which would reintroduce the drift this function exists to
// kill.
internal fun alignedReaderWidthDp(availDp: Float, density: Float): Float {
    if (!availDp.isFinite() || availDp <= 0f || density <= 0f) return availDp
    var c = floor(availDp).toInt()
    while (c > 0) {
        val px = c * density
        if (abs(px - px.roundToInt()) < 0.001f) return c.toFloat()
        c--
    }
    return floor(availDp)
}
