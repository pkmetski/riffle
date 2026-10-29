package com.riffle.app.feature.reader

import kotlin.math.floor
import kotlin.math.roundToInt

// How much width (dp) the viewport alignment is allowed to give up. See [alignedReaderWidthDp].
internal const val GUTTER_BUDGET_DP = 20

// Reader width (dp) <= availDp for single-page paginated reflowable EPUB. The goal is to pick a
// width whose Readium column-snap pitch `b` is as close as possible to the CSS column pitch
// (bodyWidth), so that turning pages accumulates the minimum left-margin bleed.
//
// HOW THE BUG WORKS
// -----------------
// Readium snaps scrollLeft to multiples of `b = Android.getViewportWidth() / devicePixelRatio`,
// where `getViewportWidth()` returns the WebView's physical pixel width. The CSS column pitch is
// `bodyWidth = ceil(physPx / webViewDpr)`. Because CSS lays out at float precision but bodyWidth
// is truncated to an integer, `b < bodyWidth` whenever physPx/webViewDpr is not exactly an
// integer. Each page turn therefore undershoots the column boundary by `bodyWidth - b` CSS px,
// and the previous page bleeds in from the left. After N pages the bleed is `N*(1 - frac(b))`
// CSS pixels — imperceptible at frac ≈ 1, very visible at frac ≈ 0.
//
// THE FIX
// -------
// Choose the integer dp value c ∈ [availDp - GUTTER_BUDGET_DP, availDp] that MAXIMISES
// frac(physPx / webViewDpr), where physPx = round(c * composeDensity). A frac of 1 (impossible
// in practice) would mean no drift; a frac near 1 means b is just below the next integer and the
// per-page bleed is sub-pixel across an entire chapter. If a perfect integer b is found (frac=0)
// it is returned immediately — no gutter, no drift.
//
// DUAL-DENSITY DEVICES (Samsung foldables)
// -----------------------------------------
// On Samsung Z Flip/Fold devices the Compose LocalDensity (composeDensity) is sourced from the
// Activity context, which may carry the primary-display's override density even when the window
// renders on the cover/secondary display. The WebView, however, uses the actual display's
// physical density (webViewDpr) for its devicePixelRatio. Passing both lets the algorithm
// optimise physPx / webViewDpr correctly without a priori knowledge of which display is active.
//
// Pass webViewDpr from `view.display?.getMetrics(dm)` → `dm.density`; it equals composeDensity
// on normal single-display phones, so the call is safe to make unconditionally.
//
// GUTTER BUDGET
// -------------
// [GUTTER_BUDGET_DP] (20 dp) is the maximum width we sacrifice to find a better-aligned value.
// Standard density buckets (2.625 = 21/8, 2.75 = 11/4, integers) always have a perfect
// whole-pixel width within 3–4 dp of availDp. Dual-density foldable cover screens (e.g. Samsung
// Z Flip, composeDensity=2.625 vs webViewDpr=2.375=19/8) need c to be a multiple of 19 for b to
// be an exact integer; the nearest such multiple can be up to ~18 dp below availDp. A larger
// budget finds that perfect c and eliminates the post-swipe settle animation that occurs when
// Readium snaps to a fractional CSS-pixel position. For pathological densities (coprime to 160)
// where no perfect c exists in budget, the algorithm falls back to the highest-frac candidate.
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
