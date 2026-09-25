package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.presenter.PageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the edge-tap page-turn geometry: left/right edge taps in the mid-vertical band of a
 * paginated reader view navigate the page; taps outside those zones fall through to the immersive
 * toggle.
 *
 * Each assertion would flip red if [pageEdgeTapDirection] were reverted to return null everywhere,
 * or if the directional mapping (left→Backward, right→Forward) were accidentally swapped.
 */
class PageEdgeTapTest {

    private val width = 360
    private val height = 800

    @Test
    fun leftEdgeMidBand_returnsBackward() {
        val dir = pageEdgeTapDirection(x = 50f, y = 400f, viewWidth = width, viewHeight = height)
        assertEquals(PageDirection.Backward, dir)
    }

    @Test
    fun rightEdgeMidBand_returnsForward() {
        val dir = pageEdgeTapDirection(x = 310f, y = 400f, viewWidth = width, viewHeight = height)
        assertEquals(PageDirection.Forward, dir)
    }

    @Test
    fun centerTap_returnsNull() {
        val dir = pageEdgeTapDirection(x = 180f, y = 400f, viewWidth = width, viewHeight = height)
        assertNull(dir)
    }

    @Test
    fun leftEdgeTopBand_returnsNull() {
        // y = 60 / 800 = 0.075, inside the top vertical guard (< 0.15)
        val dir = pageEdgeTapDirection(x = 50f, y = 60f, viewWidth = width, viewHeight = height)
        assertNull(dir)
    }

    @Test
    fun leftEdgeBottomBand_returnsNull() {
        // y = 740 / 800 = 0.925, inside the bottom vertical guard (> 0.85)
        val dir = pageEdgeTapDirection(x = 50f, y = 740f, viewWidth = width, viewHeight = height)
        assertNull(dir)
    }

    @Test
    fun rightEdgeTopBand_returnsNull() {
        val dir = pageEdgeTapDirection(x = 310f, y = 60f, viewWidth = width, viewHeight = height)
        assertNull(dir)
    }

    @Test
    fun rightEdgeBottomBand_returnsNull() {
        val dir = pageEdgeTapDirection(x = 310f, y = 740f, viewWidth = width, viewHeight = height)
        assertNull(dir)
    }

    @Test
    fun exactLeftEdgeBoundary_returnsBackward() {
        // x = 0.20 * 360 - 1 = 71, just inside the left edge zone
        val dir = pageEdgeTapDirection(x = 71f, y = 400f, viewWidth = width, viewHeight = height)
        assertEquals(PageDirection.Backward, dir)
    }

    @Test
    fun exactRightEdgeBoundary_returnsForward() {
        // x = 0.80 * 360 + 1 = 289, just inside the right edge zone
        val dir = pageEdgeTapDirection(x = 289f, y = 400f, viewWidth = width, viewHeight = height)
        assertEquals(PageDirection.Forward, dir)
    }

    @Test
    fun zeroSizeView_returnsNull() {
        val dir = pageEdgeTapDirection(x = 0f, y = 0f, viewWidth = 0, viewHeight = 0)
        assertNull(dir)
    }

    @Test
    fun customEdgeFraction_appliesCorrectly() {
        // With edgeFraction=0.10, x=50 on a 360-wide view is 0.139 — outside the 10% zone → null
        val dirOutside = pageEdgeTapDirection(x = 50f, y = 400f, viewWidth = width, viewHeight = height, edgeFraction = 0.10f)
        assertNull(dirOutside)
        // x=30 on 360-wide is 0.083 — inside the 10% zone → Backward
        val dirInside = pageEdgeTapDirection(x = 30f, y = 400f, viewWidth = width, viewHeight = height, edgeFraction = 0.10f)
        assertEquals(PageDirection.Backward, dirInside)
    }
}
