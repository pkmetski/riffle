package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Test

class ReaderViewportAlignmentTest {

    private fun resolvesToWholePixels(dp: Float, density: Float): Boolean =
        abs(dp * density - (dp * density).roundToInt()) < 0.01f

    // frac(dp * density) >= 0.5 means Compose rounds W_px up, so b = W_px/dpr >= window.innerWidth.
    // Readium's column snap never undershoots the column boundary → no left-margin bleed.
    private fun roundsUp(dp: Float, density: Float): Boolean {
        val px = dp * density
        return (px - floor(px)) >= 0.5f - 1e-6f
    }

    @Test
    fun `fractional density 2_625 trims to the nearest whole-pixel width`() {
        // 1080px / 2.625 = 411.43 → avail 411dp. 408 * 2.625 = 1071.0 (whole); 409/410/411 are not.
        assertEquals(408f, alignedReaderWidthDp(availDp = 411f, density = 2.625f))
        assertTrue(resolvesToWholePixels(408f, 2.625f))
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
    fun `result is whole pixels and never exceeds the available width for standard densities`() {
        // Standard density buckets always have a whole-pixel width within the 8dp budget.
        for (density in listOf(1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f)) {
            for (availDp in listOf(200f, 360f, 411f, 412.7f, 600f, 731f)) {
                val w = alignedReaderWidthDp(availDp, density)
                assertTrue("$w should be in 1..$availDp", w in 1f..availDp)
                assertTrue("$w * $density should be whole for standard density $density", resolvesToWholePixels(w, density))
            }
        }
    }

    // Regression: Flip8 cover-screen density (~302 dpi → 1.8875). The minimum-error candidate
    // has frac=0.0375 (rounds DOWN) → b < innerWidth → previous page bleeds from the left.
    // The fix must return a round-up candidate (frac >= 0.5) to keep b >= innerWidth.
    @Test
    fun `Flip8 cover-screen density prefers round-up over smaller absolute error that rounds down`() {
        // density 1.8875 = 151/80. availDp ~373dp (cover-screen portrait width).
        // c=373: frac=0.0375 (rounds DOWN, err=0.0375) — prior behaviour, caused left-margin bug.
        // c=365: frac=0.9375 (rounds UP, err=0.0625) — within GUTTER_BUDGET_DP=8, no left-margin bleed.
        val w = alignedReaderWidthDp(availDp = 373f, density = 1.8875f)
        assertTrue("result $w must round up (frac >= 0.5) to avoid left-margin bleed", roundsUp(w, 1.8875f))
        assertTrue("result $w must be within budget", w >= 373f - GUTTER_BUDGET_DP && w <= 373f)
    }

    @Test
    fun `non-standard density prefers round-up to prevent left-margin bleed`() {
        // density 2.855: c=379 has frac=0.045 (rounds DOWN, err=0.045 — old best).
        // c=380 has frac=0.9 (rounds UP, err=0.1) — preferred by new algorithm.
        val w = alignedReaderWidthDp(availDp = 385f, density = 2.855f)
        assertTrue("result $w must round up", roundsUp(w, 2.855f))
        assertTrue("result $w must be within budget", w >= 385f - GUTTER_BUDGET_DP && w <= 385f)
    }

    @Test
    fun `whole-pixel width is still preferred over round-up when it exists in budget`() {
        // density 2.625: c=408 has frac=0 (whole pixel, perfect alignment). Must not pick
        // a round-up candidate with higher error instead.
        val w = alignedReaderWidthDp(availDp = 411f, density = 2.625f)
        assertEquals(408f, w)
        assertTrue(resolvesToWholePixels(w, 2.625f))
    }

    @Test
    fun `degenerate inputs are returned unchanged rather than crashing`() {
        assertEquals(0f, alignedReaderWidthDp(0f, 2.625f))
        assertEquals(-5f, alignedReaderWidthDp(-5f, 2.625f))
        assertEquals(411f, alignedReaderWidthDp(411f, 0f))
    }
}
