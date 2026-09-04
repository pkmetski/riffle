package com.riffle.shared

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReaderNavRoutingTest {

    private fun item(
        ebookFormat: EbookFormat = EbookFormat.Epub,
        hasAudio: Boolean = false,
    ) = LibraryItem(
        id = "id",
        libraryId = "lib",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
        hasAudio = hasAudio,
    )

    @Test
    fun pdfItemRoutes_toPdfReader() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Pdf))
        assertIs<LibraryNav.PdfReader>(nav)
    }

    @Test
    fun epubItemRoutes_toReader() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Epub))
        assertIs<LibraryNav.Reader>(nav)
    }

    @Test
    fun cbzItemRoutes_toReader() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Cbz))
        assertIs<LibraryNav.Reader>(nav)
    }

    @Test
    fun listenableItemRoutes_toAudiobookPlayer_regardlessOfEbookFormat() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Pdf, hasAudio = true))
        assertIs<LibraryNav.AudiobookPlayer>(nav)
    }

    @Test
    fun unsupportedFormatReturnsNull() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Unsupported))
        assertNull(nav)
    }
}
