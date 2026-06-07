package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [alignedReaderWidthDp] sizes the paginated reflowable reader so Readium's column-snap pitch
 * b = physicalWidth/dpr lands on a whole number equal to CSS innerWidth, killing the right-margin
 * drift (reference_reader_right_margin_is_column_snap_bug). The chosen width must therefore map to a
 * whole number of physical pixels, never exceed the available width, and cost nothing on the
 * integer densities most phones use.
 */
class AlignedReaderWidthTest {

    /** Medium-Phone API-25: 1080px / density 2.625 = 411.43dp available. */
    @Test
    fun `picks largest whole-pixel width on density 2_625`() {
        // 408 * 2.625 = 1071.0 (whole); 409..411 * 2.625 are fractional.
        assertEquals(408f, alignedReaderWidthDp(availDp = 1080f / 2.625f, density = 2.625f))
    }

    @Test
    fun `chosen width maps to a whole number of physical pixels`() {
        val density = 2.625f
        val px = alignedReaderWidthDp(availDp = 1080f / density, density = density) * density
        assertTrue("expected whole px, got $px", abs(px - px.roundToInt()) < 0.001f)
    }

    @Test
    fun `never exceeds the available width`() {
        val avail = 1080f / 2.625f
        assertTrue(alignedReaderWidthDp(avail, 2.625f) <= avail)
    }

    @Test
    fun `costs nothing on integer densities`() {
        // density 3.0: every integer dp is already a whole pixel, so the full floor(avail) is kept.
        assertEquals(411f, alignedReaderWidthDp(availDp = 411.4f, density = 3f))
        assertEquals(411f, alignedReaderWidthDp(availDp = 411.4f, density = 2f))
        assertEquals(411f, alignedReaderWidthDp(availDp = 411.4f, density = 1f))
    }

    @Test
    fun `aligns to the density denominator on quarter densities`() {
        // density 2.75 = 11/4 -> only multiples of 4 dp are whole pixels; 408 is the largest <= 411.
        assertEquals(408f, alignedReaderWidthDp(availDp = 411.4f, density = 2.75f))
    }

    // Regression for the physical-device bug: a densityDpi coprime to 160 (here 411dpi -> 2.56875,
    // reproducible on real hardware / after a "Display size" change) used to push the exact
    // whole-pixel width 100+dp below availDp, producing huge asymmetric margins in paginated mode.
    // The gutter must now stay within the budget regardless of how ugly the density is.
    @Test
    fun `bounds the gutter on a density coprime to 160`() {
        val density = 411f / 160f // 2.56875 — whole-pixel widths are 160dp apart
        val avail = 1080f / density // ~420.4dp
        val w = alignedReaderWidthDp(avail, density)
        assertTrue("gutter ${avail - w} must stay within budget", avail - w <= GUTTER_BUDGET_DP + 1f)
        assertTrue(w <= avail)
    }

    // No matter the density, the alignment never costs more than the budget.
    @Test
    fun `gutter never exceeds the budget for any density`() {
        var dpi = 120
        while (dpi <= 640) {
            val density = dpi / 160f
            val avail = 1080f / density
            val w = alignedReaderWidthDp(avail, density)
            assertTrue("dpi=$dpi gutter=${avail - w}", avail - w <= GUTTER_BUDGET_DP + 1f)
            dpi++
        }
    }

    @Test
    fun `returns input unchanged for non-positive arguments`() {
        assertEquals(0f, alignedReaderWidthDp(availDp = 0f, density = 2.625f))
        assertEquals(411.4f, alignedReaderWidthDp(availDp = 411.4f, density = 0f))
    }
}
