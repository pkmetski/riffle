package com.riffle.feature.designsystem

import com.riffle.feature.library.CoverGridLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cover geometry that both hosts now share. Android had `coverAspectRatio` privately in
 * `LibraryItemsScreen.kt` and iOS inlined `if (coversAreSquare) 1f else 2f / 3f` at four call
 * sites; only one of the two consulted the item's own audiobook flag.
 */
class CoverGridSizingTest {

    @Test
    fun audioArtworkIsSquareAndJacketsAreTwoByThree() {
        assertEquals(1f, coverAspectRatio(square = true))
        assertEquals(2f / 3f, coverAspectRatio(square = false))
    }

    @Test
    fun expandedWindowsGetABiggerMinimumCell() {
        val phone = CoverGridLayout.minCellSizeDp(393f, 1f)
        val tablet = CoverGridLayout.minCellSizeDp(1024f, 1f)
        assertTrue(tablet > phone, "expected a larger cover cell on the Tablet Layout")
    }

    @Test
    fun theShelfCellIsDenserThanTheFullPageCell() {
        assertTrue(CoverGridLayout.shelfMinCellSizeDp(1024f, 1f) < CoverGridLayout.minCellSizeDp(1024f, 1f))
    }
}
