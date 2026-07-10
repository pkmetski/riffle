package com.riffle.app.navigation

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderRouterTest {

    @Test
    fun `epub item routes to epub_reader`() {
        assertEquals("epub_reader/item-1", readerRouteFor(item(EbookFormat.Epub)))
    }

    @Test
    fun `pdf item routes to pdf_reader`() {
        assertEquals("pdf_reader/item-1", readerRouteFor(item(EbookFormat.Pdf)))
    }

    @Test
    fun `cbz item routes to cbz_reader`() {
        assertEquals("cbz_reader/item-1", readerRouteFor(item(EbookFormat.Cbz)))
    }

    @Test
    fun `unsupported item has no route`() {
        assertNull(readerRouteFor(item(EbookFormat.Unsupported)))
    }

    // ---- Important #1 fix: sourceId threaded through the Annotations-list nav route --------

    // The regression this pins: the route must carry BOTH source=highlights and the tapped book's
    // own sourceId — the prior implementation discarded sourceId entirely (onBookClick's first
    // param was `_`), so EpubReaderViewModel had no way to distinguish "this book's server" from
    // "whatever server happens to be active when openBook() runs".
    @Test
    fun `annotations book click route carries source=highlights and the book's sourceId`() {
        val route = annotationsBookClickRoute(sourceId = "server-9", itemId = "item-1")
        assertEquals("epub_reader/item-1?source=highlights&sourceId=server-9", route)
    }

    @Test
    fun `annotations book click route URL-encodes sourceId and itemId`() {
        val route = annotationsBookClickRoute(sourceId = "server one", itemId = "item one")
        assertEquals("epub_reader/item+one?source=highlights&sourceId=server+one", route)
    }

    private fun item(ebookFormat: EbookFormat) = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
    )
}
