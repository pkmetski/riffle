package com.riffle.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.max

/**
 * Verifies the column/preview-count arithmetic used by [BookSectionGrid] and friends.
 * No Compose infrastructure needed — the formula is pure math.
 *
 * Rule: show max(1, cols × 2 − 1) items then the SeeMore tile, so the SeeMore tile
 * always fills the last slot of the second row (never orphaned on its own row).
 */
class SectionPreviewCountTest {

    // Mirrors the formula in BookSectionGrid / CoverGrid.
    private fun columns(availableWidthDp: Float, minCellDp: Float, spacingDp: Float = 8f): Int =
        max(1, floor((availableWidthDp + spacingDp) / (minCellDp + spacingDp)).toInt())

    private fun previewCount(cols: Int): Int = max(1, cols * 2 - 1)

    // ── Column counts for canonical screen sizes ──────────────────────────────

    @Test
    fun `typical phone 412dp gives 3 columns`() {
        // 412dp screen − 24dp horizontal padding = 388dp available
        // floor((388 + 8) / (112 + 8)) = floor(3.3) = 3
        assertEquals(3, columns(availableWidthDp = 388f, minCellDp = 112f))
    }

    @Test
    fun `typical tablet 840dp gives 5 columns`() {
        // 840dp screen − 24dp padding = 816dp available, tablet min cell = 140dp
        // floor((816 + 8) / (140 + 8)) = floor(5.57) = 5
        assertEquals(5, columns(availableWidthDp = 816f, minCellDp = 140f))
    }

    @Test
    fun `very narrow container falls back to 1 column`() {
        assertEquals(1, columns(availableWidthDp = 60f, minCellDp = 112f))
    }

    // ── Preview counts ────────────────────────────────────────────────────────

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
        // max(1, 1×2−1) = max(1, 1) = 1
        assertEquals(1, previewCount(1))
    }

    // ── No-orphan invariant ───────────────────────────────────────────────────

    @Test
    fun `seeMore tile always fills last slot in its row for any column count`() {
        // With cols columns, previewCount = cols×2−1, so total slots = cols×2.
        // cols×2 is always a multiple of cols → SeeMore lands at end of row 2, never alone.
        for (cols in 1..10) {
            val preview = previewCount(cols)
            val totalSlots = preview + 1 // covers + SeeMore tile
            assertEquals(
                "cols=$cols: SeeMore tile at slot $totalSlots should end a complete row",
                0, totalSlots % cols,
            )
        }
    }

    // ── SeeMore show/hide boundary ────────────────────────────────────────────

    @Test
    fun `seeMore shown only when items exceed preview count`() {
        val cols = 3
        val preview = previewCount(cols) // 5

        assertTrue("6 items should trigger SeeMore", 6 > preview)
        assertFalse("5 items exactly fits — no SeeMore", 5 > preview)
        assertFalse("4 items fits — no SeeMore", 4 > preview)
    }

    @Test
    fun `seeMore is shown at maximum pinch-zoom scale`() {
        // At 1.6× scale the min cell grows; column count shrinks; preview shrinks too.
        // Enough items (20) should still produce a SeeMore tile.
        val scaledMinCell = 112f * 1.6f // 179.2dp
        val cols = columns(availableWidthDp = 388f, minCellDp = scaledMinCell)
        assertTrue("expect ≥ 1 column", cols >= 1)
        val preview = previewCount(cols)
        assertTrue("20 items should exceed preview at max zoom", 20 > preview)
    }

    @Test
    fun `seeMore is shown at minimum pinch-zoom scale`() {
        // At 0.7× scale cells are smaller, more columns, larger preview.
        // 20 items should still overflow.
        val scaledMinCell = 112f * 0.7f // 78.4dp
        val cols = columns(availableWidthDp = 388f, minCellDp = scaledMinCell)
        val preview = previewCount(cols)
        assertTrue("20 items should exceed preview at min zoom", 20 > preview)
    }
}
