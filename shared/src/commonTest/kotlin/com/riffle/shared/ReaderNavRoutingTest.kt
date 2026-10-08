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

    // readerNavForItem — Listen action: isListenable wins over ebook format

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

    // readNavForItem — Read action: ebook format wins; listenable is last-resort fallback

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
        // An ebook+audio item: Read opens the EPUB reader, not the audiobook player.
        // This is the key difference from readerNavForItem (Listen) which routes to AudiobookPlayer.
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Epub, hasAudio = true))
        assertIs<LibraryNav.Reader>(nav)
    }

    @Test
    fun readAction_pureAudioItem_routesToAudiobookPlayer_asFallback() {
        // A pure-audio item has no readable ebook format, so fallback to AudiobookPlayer.
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Unsupported, hasAudio = true))
        assertIs<LibraryNav.AudiobookPlayer>(nav)
    }

    @Test
    fun readAction_unsupportedFormatNoAudio_returnsNull() {
        val nav = readNavForItem(item(ebookFormat = EbookFormat.Unsupported))
        assertNull(nav)
    }
}
