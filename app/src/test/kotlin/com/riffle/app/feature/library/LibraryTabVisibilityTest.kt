package com.riffle.app.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pins for issue #439's Library-tab visibility gate. The `isTabVisible` helper is what
 * `LibraryItemsScreen` uses to clamp `selectedTab` back to Home when the active Source stops
 * exposing the previously-selected tab (e.g. user was on Series, then switched to a LocalFiles
 * Source that lacks `SeriesCapability`). If any of these assertions flips red, either the tab
 * indices in `LibraryTabBar` have drifted, or a capability got silently ungated.
 */
class LibraryTabVisibilityTest {

    @Test
    fun `unconditional tabs stay visible regardless of capabilities`() {
        val none = LibraryTabVisibility.Empty
        // Home (0), Annotations (2 via tabIndexForAnnotations), All Books (5) are always shown.
        assertTrue(isTabVisible(0, none))
        assertTrue(isTabVisible(tabIndexForAnnotations(), none))
        assertTrue(isTabVisible(5, none))
    }

    @Test
    fun `to read tab is hidden without PlaylistsCapability`() {
        assertFalse(isTabVisible(1, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(1, LibraryTabVisibility.All))
    }

    @Test
    fun `series tab is hidden without SeriesCapability`() {
        assertFalse(isTabVisible(3, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(3, LibraryTabVisibility.All))
    }

    @Test
    fun `collections tab is hidden without CollectionsCapability`() {
        assertFalse(isTabVisible(4, LibraryTabVisibility.Empty))
        assertTrue(isTabVisible(4, LibraryTabVisibility.All))
    }

    @Test
    fun `a partial visibility set only affects its own tab`() {
        val onlyToRead = LibraryTabVisibility(toRead = true, series = false, collections = false)
        assertTrue(isTabVisible(1, onlyToRead))
        assertFalse(isTabVisible(3, onlyToRead))
        assertFalse(isTabVisible(4, onlyToRead))
    }
}
