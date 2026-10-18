package com.riffle.app.navigation

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.NavigationIntent
import com.riffle.feature.navigation.reduceNavStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Android-only: tests the NavController route-string translation layer. iOS navigation
// works directly with List<LibraryNav> — no route strings involved. reduceNavStack logic
// is covered by NavigationReducerTest in commonTest (runs on iOS simulator).
class LibraryNavToRouteTest {

    private fun epubItem(id: String = "item-1", sourceId: String = "src-1") = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        hasAudio = false,
        sourceId = sourceId,
    )

    private fun audioItem(id: String = "audio-1", sourceId: String = "src-1") = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported,
        hasAudio = true,
        sourceId = sourceId,
    )

    private fun pdfItem(id: String = "pdf-1", sourceId: String = "src-1") = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        hasAudio = false,
        sourceId = sourceId,
    )

    @Test
    fun readerNav_epubItem_producesEpubReaderRoute() {
        val route = libraryNavToAndroidRoute(LibraryNav.Reader(epubItem()))
        assertEquals("epub_reader/item-1?sourceId=src-1", route)
    }

    @Test
    fun readerNav_epubItem_noSourceId_omitsSourceIdParam() {
        val item = epubItem(sourceId = "")
        val route = libraryNavToAndroidRoute(LibraryNav.Reader(item))
        assertEquals("epub_reader/item-1", route)
    }

    @Test
    fun readerNav_pdfItem_producesPdfReaderRoute() {
        val route = libraryNavToAndroidRoute(LibraryNav.PdfReader(pdfItem()))
        assertEquals("pdf_reader/pdf-1?sourceId=src-1", route)
    }

    @Test
    fun readerNav_cbzItem_producesCbzReaderRoute() {
        val item = LibraryItem(
            id = "cbz-1", libraryId = "lib", title = "T", author = "A",
            coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
            ebookFormat = EbookFormat.Cbz, hasAudio = false, sourceId = "src-1",
        )
        val route = libraryNavToAndroidRoute(LibraryNav.CbzReader(item))
        assertEquals("cbz_reader/cbz-1?sourceId=src-1", route)
    }

    @Test
    fun audiobookPlayerNav_producesAudiobookPlayerRoute() {
        val route = libraryNavToAndroidRoute(LibraryNav.AudiobookPlayer(audioItem()))
        assertEquals("audiobook_player/src-1/audio-1", route)
    }

    @Test
    fun audiobookPlayerNav_withStartAtSec_includesStartAtSec() {
        val route = libraryNavToAndroidRoute(LibraryNav.AudiobookPlayer(audioItem(), startAtSec = 42.5))
        assertEquals("audiobook_player/src-1/audio-1?startAtSec=42.5", route)
    }

    @Test
    fun audiobookPlayerNav_withPlaylist_includesPlaylistParams() {
        val route = libraryNavToAndroidRoute(
            LibraryNav.AudiobookPlayer(audioItem(), playlistId = "pl-1", playlistLibraryId = "lib-1"),
        )
        assertEquals("audiobook_player/src-1/audio-1?playlistId=pl-1&libraryId=lib-1", route)
    }

    @Test
    fun elidedReaderNav_producesHighlightsRoute() {
        val route = libraryNavToAndroidRoute(LibraryNav.ElidedReader("item-1", "src-1"))
        assertEquals("epub_reader/item-1?source=highlights&sourceId=src-1", route)
    }

    @Test
    fun audiobookBookmarkNav_producesAudiobookPlayerRouteWithStartAtSec() {
        val route = libraryNavToAndroidRoute(LibraryNav.AudiobookBookmark("item-1", "src-1", 77.0))
        assertEquals("audiobook_player/src-1/item-1?startAtSec=77.0", route)
    }

    @Test
    fun itemDetailNav_returnsNull() {
        assertNull(libraryNavToAndroidRoute(LibraryNav.ItemDetail("item-1", "src-1")))
    }

    @Test
    fun readerNav_withStartHref_includesStartTocHref() {
        val route = libraryNavToAndroidRoute(LibraryNav.Reader(epubItem(), startHref = "chapter2.xhtml"))
        assertEquals("epub_reader/item-1?sourceId=src-1&startTocHref=chapter2.xhtml", route)
    }

    @Test
    fun navigateForIntent_readEpub_producesEpubRoute() {
        val dest = reduceNavStack(NavigationIntent.ReadItem(epubItem()), emptyList()).lastOrNull()
        val route = libraryNavToAndroidRoute(dest!!)
        assertEquals("epub_reader/item-1?sourceId=src-1", route)
    }

    @Test
    fun navigateForIntent_listenAudio_producesAudiobookRoute() {
        val dest = reduceNavStack(NavigationIntent.ListenItem(audioItem()), emptyList()).lastOrNull()
        val route = libraryNavToAndroidRoute(dest!!)
        assertEquals("audiobook_player/src-1/audio-1", route)
    }

    @Test
    fun navigateForIntent_readItemAtHref_includesHrefParam() {
        val dest = reduceNavStack(NavigationIntent.ReadItemAtHref(epubItem(), "ch3.xhtml"), emptyList()).lastOrNull()
        val route = libraryNavToAndroidRoute(dest!!)
        assertEquals("epub_reader/item-1?sourceId=src-1&startTocHref=ch3.xhtml", route)
    }

    @Test
    fun navigateForIntent_readUnsupportedItem_returnsNoRoute() {
        val unsupported = LibraryItem(
            id = "x", libraryId = "lib", title = "T", author = "A",
            coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
            ebookFormat = EbookFormat.Unsupported, hasAudio = false,
        )
        val dest = reduceNavStack(NavigationIntent.ReadItem(unsupported), emptyList()).lastOrNull()
        assertTrue(dest == null)
    }

    @Test
    fun navigateForIntent_openAudiobookBookmark_producesAudiobookPlayerRouteWithStartAtSec() {
        val dest = reduceNavStack(
            NavigationIntent.OpenAudiobookBookmark("item-1", "src-1", 99.9),
            emptyList(),
        ).lastOrNull()
        val route = libraryNavToAndroidRoute(dest!!)
        assertEquals("audiobook_player/src-1/item-1?startAtSec=99.9", route)
    }

    @Test
    fun readerNav_specialCharsInIds_areUrlEncoded() {
        val item = epubItem(id = "item one", sourceId = "server one")
        val route = libraryNavToAndroidRoute(LibraryNav.Reader(item))
        assertEquals("epub_reader/item+one?sourceId=server+one", route)
    }
}
