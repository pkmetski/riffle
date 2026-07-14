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
}
