package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Test

class ReaderViewportAlignmentTest {

    // The point of the alignment: the chosen dp width must resolve to a WHOLE number of physical
    // pixels, so innerWidth (== physicalPx / dpr) is an integer == Readium's page-snap pitch.
    private fun resolvesToWholePixels(dp: Float, density: Float): Boolean =
        abs(dp * density - (dp * density).roundToInt()) < 0.01f

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
    fun `result is whole pixels and never exceeds the available width`() {
        for (density in listOf(1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f)) {
            for (availDp in listOf(200f, 360f, 411f, 412.7f, 600f, 731f)) {
                val w = alignedReaderWidthDp(availDp, density)
                assertTrue("$w should be in 1..$availDp", w in 1f..availDp)
                assertTrue("$w * $density should be whole", resolvesToWholePixels(w, density))
            }
        }
    }

    @Test
    fun `degenerate inputs are returned unchanged rather than crashing`() {
        assertEquals(0f, alignedReaderWidthDp(0f, 2.625f))
        assertEquals(-5f, alignedReaderWidthDp(-5f, 2.625f))
        assertEquals(411f, alignedReaderWidthDp(411f, 0f))
    }
}
