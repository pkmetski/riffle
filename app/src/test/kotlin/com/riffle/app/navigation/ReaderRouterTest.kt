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
    fun `unsupported item has no route`() {
        assertNull(readerRouteFor(item(EbookFormat.Unsupported)))
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
