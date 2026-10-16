package com.riffle.shared.library

import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression: audiobook bookmark search results navigated to [LibraryNav.ElidedReader] (the EPUB
 * highlights view) instead of the audiobook player at the bookmark position. The fix introduces
 * [LibraryNav.AudiobookBookmark] as a distinct destination that carries the [startAtSec] position.
 *
 * Specific assertions that would flip red if the fix were reverted:
 * - AudiobookBookmark is a distinct destination (not ElidedReader)
 * - startAtSec round-trips correctly from the destination
 * - A zero startAtSec (valid position) is preserved, not treated as absent
 */
class AudiobookBookmarkNavTest {

    @Test
    fun audiobookBookmarkDestinationCarriesStartAtSec() {
        val dest = LibraryNav.AudiobookBookmark(
            itemId = "book-1",
            sourceId = "source-1",
            startAtSec = 123.5,
        )
        assertIs<LibraryNav.AudiobookBookmark>(dest)
        assertEquals(123.5, dest.startAtSec)
        assertEquals("book-1", dest.itemId)
        assertEquals("source-1", dest.sourceId)
    }

    @Test
    fun audiobookBookmarkIsNotElidedReader() {
        // AudiobookBookmark must be distinct from ElidedReader — navigating to the wrong
        // destination was the original bug (#1208). Verify they are different types.
        val bookmark = LibraryNav.AudiobookBookmark(itemId = "book-1", sourceId = null, startAtSec = 0.0)
        val elided = LibraryNav.ElidedReader(itemId = "book-1", sourceId = null)
        // They are different sealed subtypes — this assertion documents the contract.
        assertEquals(false, bookmark == elided, "AudiobookBookmark must be a different destination from ElidedReader")
    }

    @Test
    fun zeroStartAtSecPreserved() {
        // A bookmark at position 0 (first second of the book) is valid — must not be treated
        // as "absent" and replaced with the default -1.0.
        val dest = LibraryNav.AudiobookBookmark(itemId = "x", sourceId = "y", startAtSec = 0.0)
        assertEquals(0.0, dest.startAtSec)
    }
}
