package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Test

class ReaderViewportAlignmentTest {

    // frac(physPx / webViewDpr) where physPx = round(dp * composeDensity).
    // Drift per page = 1 - this value; higher is better.
    private fun bFrac(dp: Float, composeDensity: Float, webViewDpr: Float = composeDensity): Float {
        val physPx = (dp * composeDensity).roundToInt().toFloat()
        val b = physPx / webViewDpr
        return b - floor(b)
    }

    private fun resolvesToWholeB(dp: Float, composeDensity: Float, webViewDpr: Float = composeDensity): Boolean =
        bFrac(dp, composeDensity, webViewDpr) < 0.01f

    @Test
    fun `fractional density 2_625 trims to the nearest whole-pixel width`() {
        // 1080px / 2.625 = 411.43 → avail 411dp. 408 * 2.625 = 1071.0 (whole); 409/410/411 are not.
        assertEquals(408f, alignedReaderWidthDp(availDp = 411f, composeDensity = 2.625f))
        assertTrue(resolvesToWholeB(408f, 2.625f))
    }

    @Test
    fun `integer density keeps the full floored width (no gutter)`() {
        assertEquals(411f, alignedReaderWidthDp(411f, 2.0f))
        assertEquals(411f, alignedReaderWidthDp(411f, 3.0f))
        assertEquals(360f, alignedReaderWidthDp(360.4f, 1.0f))
    }

    @Test
    fun `2_75 density (440dpi) keeps widths divisible by four`() {
        // 2.75 = 11/4, so dp must be a multiple of 4 to land on a whole pixel. 411 → 408.
        assertEquals(408f, alignedReaderWidthDp(411f, 2.75f))
    }

    @Test
    fun `result is whole pixels and never exceeds the available width`() {
        // Standard density buckets always have a whole-pixel width within the gutter budget.
        for (density in listOf(1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f)) {
            for (availDp in listOf(200f, 360f, 411f, 412.7f, 600f, 731f)) {
                val w = alignedReaderWidthDp(availDp, density)
                assertTrue("$w should be in 1..$availDp", w in 1f..availDp)
                assertTrue("$w * $density should be whole for standard density $density", resolvesToWholeB(w, density))
            }
        }
    }

    // Regression: cover screen with composeDensity != webViewDpr (Samsung foldable density mismatch).
    // The Compose Activity context uses the primary display's override density (2.625) while the
    // WebView renders on the cover display's physical density (2.375 = 19/8). The algorithm must use
    // physPx/webViewDpr to compute b, and the larger GUTTER_BUDGET_DP (20) must reach the nearest c
    // that makes b an exact integer — eliminating the post-swipe settle animation.
    //
    // For b to be integer: round(c * 21/8) must be divisible by 19; c must be a multiple of 19.
    // Nearest multiple of 19 below 473 is 456 (=19*24): physPx=1197, b=1197/2.375=504.0 exactly.
    @Test
    fun `dual-density device finds perfect integer b within extended budget`() {
        // Samsung Flip cover screen: composeDensity=2.625 (main-display override), webViewDpr=2.375 (physical).
        val composeDensity = 2.625f
        val webViewDpr = 2.375f
        val availDp = 473.90f
        val w = alignedReaderWidthDp(availDp, composeDensity, webViewDpr)
        // c=456 (physPx=1197) gives b=1197/2.375=504.0 — exact integer, zero drift, no post-swipe settle.
        assertEquals(456f, w)
        val frac = bFrac(w, composeDensity, webViewDpr)
        assertTrue("frac(b)=$frac must be < 1e-5 for a perfect integer b", frac < 1e-5f)
        assertTrue("result $w must not exceed availDp", w <= availDp)
    }

    // Regression: non-standard single density (e.g. display-size override) must maximise frac(b),
    // NOT use the old "prefer round-up of (c * density)" heuristic which was wrong for density > 1.
    //
    // For density=1.8875 (302dpi / 160 = Flip5 cover-screen bucket), availDp=373:
    // c=373: physPx=704, b=704/1.8875=372.980, frac(b)=0.980, drift=0.020/page — BEST.
    // c=365: physPx=689, b=689/1.8875=365.033, frac(b)=0.033, drift=0.967/page — old algorithm (WRONG).
    // The old algorithm preferred c=365 because frac(365*1.8875)=0.9375 >= 0.5 ("rounds up"),
    // but that criterion was a broken proxy. frac(b) directly measures drift.
    @Test
    fun `non-standard density maximises frac of b to minimise per-page drift`() {
        val density = 1.8875f
        val availDp = 373f
        val w = alignedReaderWidthDp(availDp, density)
        // c=373: frac(b)=0.980. Old algorithm returned c=365: frac(b)=0.033.
        assertEquals(373f, w)
        val frac = bFrac(w, density)
        assertTrue("frac(b)=$frac must be > 0.9", frac > 0.9f)
        assertTrue("result $w must be within budget", w >= availDp - GUTTER_BUDGET_DP && w <= availDp)
    }

    @Test
    fun `another non-standard density maximises frac of b`() {
        // density 2.855: the algorithm must find the c in budget with highest frac(physPx/2.855).
        val density = 2.855f
        val availDp = 385f
        val w = alignedReaderWidthDp(availDp, density)
        val frac = bFrac(w, density)
        assertTrue("frac(b)=$frac must be > 0.9", frac > 0.9f)
        assertTrue("result $w must be within budget", w >= availDp - GUTTER_BUDGET_DP && w <= availDp)
    }

    @Test
    fun `whole-pixel width is still preferred over high-frac non-perfect when perfect exists`() {
        // density 2.625: c=408 has frac(b)=0 (perfect). Must return 408, not a non-perfect candidate.
        val w = alignedReaderWidthDp(availDp = 411f, composeDensity = 2.625f)
        assertEquals(408f, w)
        assertTrue(resolvesToWholeB(w, 2.625f))
    }

    @Test
    fun `degenerate inputs are returned unchanged rather than crashing`() {
        assertEquals(0f, alignedReaderWidthDp(0f, 2.625f))
        assertEquals(-5f, alignedReaderWidthDp(-5f, 2.625f))
        assertEquals(411f, alignedReaderWidthDp(411f, 0f))
    }
}
