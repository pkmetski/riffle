package com.riffle.app.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `isTabVisible` clamp helper — Home (0) and All Books (5) stay visible no matter what,
 * and each optional tab is gated purely on its own emptiness bit. If any of these flip red, either
 * the tab indices in `LibraryTabBar` have drifted or the emptiness routing has been rewired.
 */
class LibraryTabVisibilityTest {

    @Test
    fun `home and all books are always visible`() {
        val none = LibraryTabVisibility.Empty
        assertTrue(isTabVisible(0, none))
        assertTrue(isTabVisible(5, none))
    }

    @Test
    fun `to read tab is hidden when the to-read set is empty`() {
        assertFalse(isTabVisible(1, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(1, LibraryTabVisibility.All))
    }

    @Test
    fun `annotations tab is hidden when the library has no annotations`() {
        assertFalse(isTabVisible(tabIndexForAnnotations(), LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(tabIndexForAnnotations(), LibraryTabVisibility.All))
    }

    @Test
    fun `series tab is hidden when there are no series`() {
        assertFalse(isTabVisible(3, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(3, LibraryTabVisibility.All))
    }

    @Test
    fun `collections tab is hidden when there are no collections`() {
        assertFalse(isTabVisible(4, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(4, LibraryTabVisibility.All))
    }

    @Test
    fun `each optional tab is gated independently`() {
        val onlyToRead = LibraryTabVisibility(
            toRead = true, series = false, collections = false, annotations = false,
        )
        assertTrue(isTabVisible(1, onlyToRead))
        assertFalse(isTabVisible(tabIndexForAnnotations(), onlyToRead))
        assertFalse(isTabVisible(3, onlyToRead))
        assertFalse(isTabVisible(4, onlyToRead))
    }

    // Regression pin for the search-clearing clobber: `LibraryFilterEngine` filters
    // `projection.series/collections` by the active query, so a search that yields no series
    // would flip visibility.series off. Without this guard the LaunchedEffect clamps the user's
    // Series tab to Home, and the clamp survives clearing the search.
    @Test
    fun `shouldClampSelectedTab returns false while the user is searching`() {
        val noSeries = LibraryTabVisibility(
            toRead = true, series = false, collections = true, annotations = true,
        )
        // User was on Series (index 3), types a query with no matches → do NOT clamp.
        assertFalse(shouldClampSelectedTab("dune", noSeries, selectedTab = 3))
    }

    @Test
    fun `shouldClampSelectedTab returns false while visibility is still resolving`() {
        // Cold start — visibility hasn't emitted a real value yet.
        assertFalse(shouldClampSelectedTab("", visibility = null, selectedTab = 3))
    }

    @Test
    fun `shouldClampSelectedTab returns true only when the tab is truly unavailable`() {
        val noSeries = LibraryTabVisibility(
            toRead = true, series = false, collections = true, annotations = true,
        )
        // User was on Series, no search, series was deleted → clamp.
        assertTrue(shouldClampSelectedTab("", noSeries, selectedTab = 3))
        // User was on Collections, which still has data → don't clamp.
        assertFalse(shouldClampSelectedTab("", noSeries, selectedTab = 4))
    }
}
