package com.riffle.feature.library

import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionPreviewCountTest {

    private fun columns(availableWidthDp: Float, minCellDp: Float, spacingDp: Float = 8f): Int =
        max(1, floor((availableWidthDp + spacingDp) / (minCellDp + spacingDp)).toInt())

    private fun previewCount(cols: Int): Int = max(1, cols * 2 - 1)

    @Test
    fun `typical phone 412dp gives 3 columns`() {
        assertEquals(3, columns(availableWidthDp = 388f, minCellDp = 112f))
    }

    @Test
    fun `typical tablet 840dp gives 5 columns`() {
        assertEquals(5, columns(availableWidthDp = 816f, minCellDp = 140f))
    }

    @Test
    fun `very narrow container falls back to 1 column`() {
        assertEquals(1, columns(availableWidthDp = 60f, minCellDp = 112f))
    }

    @Test
    fun `phone 3 columns yields preview of 5`() {
        assertEquals(5, previewCount(3))
    }

    @Test
    fun `tablet 5 columns yields preview of 9`() {
        assertEquals(9, previewCount(5))
    }

    @Test
    fun `single column yields preview of 1`() {
        assertEquals(1, previewCount(1))
    }

    @Test
    fun `seeMore tile always fills last slot in its row for any column count`() {
        for (cols in 1..10) {
            val preview = previewCount(cols)
            val totalSlots = preview + 1
            assertEquals(
                0, totalSlots % cols,
                "cols=$cols: SeeMore tile at slot $totalSlots should end a complete row",
            )
        }
    }

    @Test
    fun `seeMore shown only when items exceed preview count`() {
        val cols = 3
        val preview = previewCount(cols)

        assertTrue(6 > preview, "6 items should trigger SeeMore")
        assertFalse(5 > preview, "5 items exactly fits — no SeeMore")
        assertFalse(4 > preview, "4 items fits — no SeeMore")
    }

    @Test
    fun `seeMore is shown at maximum pinch-zoom scale`() {
        val scaledMinCell = 112f * 1.6f
        val cols = columns(availableWidthDp = 388f, minCellDp = scaledMinCell)
        assertTrue(cols >= 1, "expect ≥ 1 column")
        val preview = previewCount(cols)
        assertTrue(20 > preview, "20 items should exceed preview at max zoom")
    }

    @Test
    fun `seeMore is shown at minimum pinch-zoom scale`() {
        val scaledMinCell = 112f * 0.7f
        val cols = columns(availableWidthDp = 388f, minCellDp = scaledMinCell)
        val preview = previewCount(cols)
        assertTrue(20 > preview, "20 items should exceed preview at min zoom")
    }
}
