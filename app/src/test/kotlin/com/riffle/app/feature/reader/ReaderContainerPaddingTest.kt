package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readerContainerPaddingPx] reserves a stable top/bottom margin on the Readium fragment
 * container in paginated reflowable mode only. In scroll mode the container fills the screen
 * and the WebView sits permanently below topPx — content scrolls *inside* the WebView so that
 * gap never closes, producing a permanent white strip (regression in #175).
 */
class ReaderContainerPaddingTest {

    private val density = 3f

    // Regression guard for the #175 bug: scroll mode must produce zero container padding.
    // Applying padding in scroll mode shifts the WebView below the container top permanently
    // (content scrolls inside the WebView, not the container), leaving a visible white strip.
    @Test
    fun `scroll mode produces zero padding regardless of margins setting`() {
        val (top, bottom) = readerContainerPaddingPx(
            margins = 1.5f,
            density = density,
            isFixedLayout = false,
            isScrollMode = true,
        )
        assertEquals("top must be 0 in scroll mode", 0, top)
        assertEquals("bottom must be 0 in scroll mode", 0, bottom)
    }

    @Test
    fun `fixed-layout produces zero padding regardless of scroll mode`() {
        val paginated = readerContainerPaddingPx(
            margins = 1.0f, density = density, isFixedLayout = true, isScrollMode = false,
        )
        val scroll = readerContainerPaddingPx(
            margins = 1.0f, density = density, isFixedLayout = true, isScrollMode = true,
        )
        assertEquals(0, paginated.first)
        assertEquals(0, paginated.second)
        assertEquals(0, scroll.first)
        assertEquals(0, scroll.second)
    }

    @Test
    fun `paginated reflowable produces non-zero padding`() {
        val (top, bottom) = readerContainerPaddingPx(
            margins = 1.0f,
            density = density,
            isFixedLayout = false,
            isScrollMode = false,
        )
        assertTrue("top must be > 0 for paginated reflowable", top > 0)
        assertTrue("bottom must be > 0 for paginated reflowable", bottom > 0)
    }

    @Test
    fun `top scales with margins preference`() {
        val (top1, _) = readerContainerPaddingPx(margins = 1.0f, density = density, isFixedLayout = false, isScrollMode = false)
        val (top2, _) = readerContainerPaddingPx(margins = 2.0f, density = density, isFixedLayout = false, isScrollMode = false)
        assertTrue("top at margins=2.0 must exceed top at margins=1.0", top2 > top1)
    }

    @Test
    fun `bottom is larger than top at same margins (1_0x vs 0_8x ratio)`() {
        val (top, bottom) = readerContainerPaddingPx(
            margins = 1.0f, density = density, isFixedLayout = false, isScrollMode = false,
        )
        assertTrue("bottom (1.0×) must exceed top (0.8×)", bottom > top)
    }

    // Concrete pixel values for density=3 and margins=1.0: top=16*1.0*3=48px, bottom=20*1.0*3=60px.
    @Test
    fun `produces expected pixel values at density 3 and margins 1_0`() {
        val (top, bottom) = readerContainerPaddingPx(
            margins = 1.0f, density = 3f, isFixedLayout = false, isScrollMode = false,
        )
        assertEquals(48, top)
        assertEquals(60, bottom)
    }

    @Test
    fun `zero margins yields zero padding even in paginated mode`() {
        val (top, bottom) = readerContainerPaddingPx(
            margins = 0f, density = density, isFixedLayout = false, isScrollMode = false,
        )
        assertEquals(0, top)
        assertEquals(0, bottom)
    }
}
