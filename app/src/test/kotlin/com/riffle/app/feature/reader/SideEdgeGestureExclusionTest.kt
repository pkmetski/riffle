package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideEdgeGestureExclusionTest {

    @Test
    fun `gesture insets produce full-height strips hugging each side edge`() {
        val rects = computeSideEdgeExclusions(width = 1080, height = 1920, leftGestureInset = 40, rightGestureInset = 40)

        assertEquals(
            listOf(
                EdgeExclusion(0, 0, 40, 1920),
                EdgeExclusion(1040, 0, 1080, 1920),
            ),
            rects,
        )
    }

    @Test
    fun `zero gesture insets exclude nothing (3-button nav has no side back-gesture)`() {
        assertTrue(computeSideEdgeExclusions(width = 1080, height = 1920, leftGestureInset = 0, rightGestureInset = 0).isEmpty())
    }

    @Test
    fun `a single non-zero edge yields only that strip`() {
        assertEquals(
            listOf(EdgeExclusion(1040, 0, 1080, 1920)),
            computeSideEdgeExclusions(width = 1080, height = 1920, leftGestureInset = 0, rightGestureInset = 40),
        )
    }

    @Test
    fun `unmeasured view excludes nothing`() {
        assertTrue(computeSideEdgeExclusions(width = 0, height = 0, leftGestureInset = 40, rightGestureInset = 40).isEmpty())
    }
}
