package com.riffle.shared

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the predicate used in HomeScreen to decide whether the navigation drawer should
 * be disabled — i.e. which LibraryNav destinations count as "in an ebook reader".
 *
 * The rule mirrors Android's `isReaderRoute()`:
 *   - EPUB, PDF, CBZ readers → drawer disabled
 *   - ElidedReader (Annotations View) → drawer disabled
 *   - AudiobookPlayer → drawer ENABLED (user can switch libraries while playing)
 */
class LibraryNavDrawerGatingTest {

    private fun isEbookReader(nav: LibraryNav): Boolean =
        nav is LibraryNav.Reader ||
            nav is LibraryNav.PdfReader ||
            nav is LibraryNav.CbzReader ||
            nav is LibraryNav.ElidedReader

    private val stubItem = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "Stub",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    @Test
    fun epubReaderDisablesDrawer() {
        assertTrue(isEbookReader(LibraryNav.Reader(stubItem)))
    }

    @Test
    fun pdfReaderDisablesDrawer() {
        assertTrue(isEbookReader(LibraryNav.PdfReader(stubItem)))
    }

    @Test
    fun cbzReaderDisablesDrawer() {
        assertTrue(isEbookReader(LibraryNav.CbzReader(stubItem)))
    }

    @Test
    fun elidedReaderDisablesDrawer() {
        assertTrue(isEbookReader(LibraryNav.ElidedReader("item-1", "src-1")))
    }

    @Test
    fun audiobookPlayerEnablesDrawer() {
        assertFalse(isEbookReader(LibraryNav.AudiobookPlayer(stubItem)))
    }

    @Test
    fun libraryItemsEnablesDrawer() {
        assertFalse(isEbookReader(LibraryNav.Items))
    }

    @Test
    fun itemDetailEnablesDrawer() {
        assertFalse(isEbookReader(LibraryNav.ItemDetail("item-1", "src-1")))
    }
}
