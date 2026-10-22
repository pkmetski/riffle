package com.riffle.feature.navigation

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.reader.highlights.ReaderSource
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun cbzItemRoutes_toCbzReader() {
        val nav = readerNavForItem(item(ebookFormat = EbookFormat.Cbz))
        assertIs<LibraryNav.CbzReader>(nav)
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

    @Test
    fun readAction_epubItem_routesToReader() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Epub))
        assertIs<LibraryNav.Reader>(nav)
    }

    @Test
    fun readAction_pdfItem_routesToPdfReader() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Pdf))
        assertIs<LibraryNav.PdfReader>(nav)
    }

    @Test
    fun readAction_cbzItem_routesToCbzReader() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Cbz))
        assertIs<LibraryNav.CbzReader>(nav)
    }

    @Test
    fun readAction_ebookAndAudioItem_routesToReader_notAudiobookPlayer() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Epub, hasAudio = true))
        assertIs<LibraryNav.Reader>(nav)
    }

    @Test
    fun readAction_pureAudioItem_routesToAudiobookPlayer_asFallback() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Unsupported, hasAudio = true))
        assertIs<LibraryNav.AudiobookPlayer>(nav)
    }

    @Test
    fun readAction_unsupportedFormatNoAudio_returnsNull() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Unsupported))
        assertNull(nav)
    }

    @Test
    fun readAtHref_epubItem_routesToReaderWithTocHref() {
        val href = "chapter3.xhtml"
        val nav = readNavForItemAtHref(item(ebookFormat = EbookFormat.Epub), href)
        assertIs<LibraryNav.Reader>(nav)
        kotlin.test.assertEquals(href, nav.startHref)
    }

    @Test
    fun readAtHref_epubItem_fragmentHref_routesToReaderWithFullHref() {
        val href = "part1.xhtml#section-2"
        val nav = readNavForItemAtHref(item(ebookFormat = EbookFormat.Epub), href)
        assertIs<LibraryNav.Reader>(nav)
        kotlin.test.assertEquals(href, nav.startHref)
    }

    @Test
    fun readAtHref_pdfItem_returnsNull() {
        val nav = readNavForItemAtHref(item(ebookFormat = EbookFormat.Pdf), "page1.xhtml")
        assertNull(nav)
    }

    @Test
    fun readAtHref_cbzItem_returnsNull() {
        val nav = readNavForItemAtHref(item(ebookFormat = EbookFormat.Cbz), "page1.xhtml")
        assertNull(nav)
    }

    @Test
    fun readAtHref_unsupportedItem_returnsNull() {
        val nav = readNavForItemAtHref(item(ebookFormat = EbookFormat.Unsupported), "page1.xhtml")
        assertNull(nav)
    }

    @Test
    fun readerNavDefaultsToFullBookSource() {
        val nav = readerNavForItem(item()) as LibraryNav.Reader
        assertEquals(ReaderSource.FullBook, nav.readerSource)
    }

    @Test
    fun readerNavAcceptsHighlightsSource() {
        // Regression: ElidedReaderLoader used to open LibraryNav.Reader with no readerSource,
        // so iOS always opened the full EPUB instead of the synthesised highlights-only publication.
        // Fix: readerSource = ReaderSource.Highlights is passed by ElidedReaderLoader.
        val nav = LibraryNav.Reader(item(), readerSource = ReaderSource.Highlights)
        assertEquals(ReaderSource.Highlights, nav.readerSource)
    }
}
