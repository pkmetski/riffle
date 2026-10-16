package com.riffle.feature.navigation

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavigationReducerTest {

    private fun epubItem(id: String = "item-1") = LibraryItem(
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
    )

    private fun audioItem(id: String = "audio-1") = LibraryItem(
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
    )

    private fun pdfItem(id: String = "pdf-1") = LibraryItem(
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
    )

    @Test
    fun readItem_epub_pushesReaderDestination() {
        val result = reduceNavStack(NavigationIntent.ReadItem(epubItem()), emptyList())
        assertEquals(1, result.size)
        assertIs<LibraryNav.Reader>(result.last())
    }

    @Test
    fun readItem_pdf_pushesPdfReader() {
        val result = reduceNavStack(NavigationIntent.ReadItem(pdfItem()), emptyList())
        assertIs<LibraryNav.PdfReader>(result.last())
    }

    @Test
    fun readItem_unsupportedFormat_doesNotPush() {
        val item = LibraryItem(
            id = "x", libraryId = "lib", title = "T", author = "A",
            coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
            ebookFormat = EbookFormat.Unsupported, hasAudio = false,
        )
        val result = reduceNavStack(NavigationIntent.ReadItem(item), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun listenItem_audio_pushesAudiobookPlayer() {
        val result = reduceNavStack(NavigationIntent.ListenItem(audioItem()), emptyList())
        assertIs<LibraryNav.AudiobookPlayer>(result.last())
    }

    @Test
    fun readItemAtHref_epub_pushesReaderWithStartHref() {
        val href = "chapter2.xhtml"
        val result = reduceNavStack(NavigationIntent.ReadItemAtHref(epubItem(), href), emptyList())
        val dest = assertIs<LibraryNav.Reader>(result.last())
        assertEquals(href, dest.startHref)
    }

    @Test
    fun readItemAtHref_pdf_doesNotPush() {
        val result = reduceNavStack(NavigationIntent.ReadItemAtHref(pdfItem(), "ch.xhtml"), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun listenItemAtSec_pushesAudiobookPlayerWithStartAtSec() {
        val result = reduceNavStack(NavigationIntent.ListenItemAtSec(audioItem(), 42.5), emptyList())
        val dest = assertIs<LibraryNav.AudiobookPlayer>(result.last())
        assertEquals(42.5, dest.startAtSec)
    }

    @Test
    fun openElidedReader_pushesElidedReader() {
        val result = reduceNavStack(NavigationIntent.OpenElidedReader("item-1", "src-1"), emptyList())
        val dest = assertIs<LibraryNav.ElidedReader>(result.last())
        assertEquals("item-1", dest.itemId)
        assertEquals("src-1", dest.sourceId)
    }

    @Test
    fun openAudiobookBookmark_pushesAudiobookBookmark() {
        val result = reduceNavStack(NavigationIntent.OpenAudiobookBookmark("item-1", "src-1", 77.0), emptyList())
        val dest = assertIs<LibraryNav.AudiobookBookmark>(result.last())
        assertEquals(77.0, dest.startAtSec)
    }

    @Test
    fun openItemDetail_pushesItemDetail() {
        val result = reduceNavStack(NavigationIntent.OpenItemDetail("item-1", "src-1"), emptyList())
        assertIs<LibraryNav.ItemDetail>(result.last())
    }

    @Test
    fun openSeriesDetail_pushesSeriesDetail() {
        val result = reduceNavStack(NavigationIntent.OpenSeriesDetail("lib-1", "series-1", "My Series"), emptyList())
        val dest = assertIs<LibraryNav.SeriesDetail>(result.last())
        assertEquals("series-1", dest.seriesId)
        assertEquals("lib-1", dest.seriesLibraryId)
        assertEquals("My Series", dest.seriesName)
    }

    @Test
    fun openCollectionDetail_pushesCollectionDetail() {
        val result = reduceNavStack(NavigationIntent.OpenCollectionDetail("lib-1", "col-1", "My Collection"), emptyList())
        val dest = assertIs<LibraryNav.CollectionDetail>(result.last())
        assertEquals("col-1", dest.collectionId)
        assertEquals("lib-1", dest.collectionLibraryId)
        assertEquals("My Collection", dest.collectionName)
    }

    @Test
    fun showAnnotationSearch_pushesAnnotationSearch() {
        val result = reduceNavStack(NavigationIntent.ShowAnnotationSearch("lib-1", "query"), emptyList())
        val dest = assertIs<LibraryNav.AnnotationSearch>(result.last())
        assertEquals("lib-1", dest.searchLibraryId)
        assertEquals("query", dest.query)
    }

    @Test
    fun openFilteredBooks_pushesFilteredBooks() {
        val result = reduceNavStack(
            NavigationIntent.OpenFilteredBooks("lib-1", com.riffle.feature.library.FacetType.AUTHOR, "Tolkien"),
            emptyList(),
        )
        val dest = assertIs<LibraryNav.FilteredBooks>(result.last())
        assertEquals("lib-1", dest.facetLibraryId)
        assertEquals("Tolkien", dest.facetValue)
    }

    @Test
    fun back_dropsLastEntry() {
        val stack = listOf(LibraryNav.Items, LibraryNav.ItemDetail("x", null))
        val result = reduceNavStack(NavigationIntent.Back, stack)
        assertEquals(listOf(LibraryNav.Items), result)
    }

    @Test
    fun back_onEmptyStack_returnsEmptyStack() {
        val result = reduceNavStack(NavigationIntent.Back, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun listenItem_deduplicatesWhenAlreadyAtSamePlayer() {
        val item = audioItem()
        val stack = listOf(LibraryNav.Items, LibraryNav.AudiobookPlayer(item))
        val result = reduceNavStack(NavigationIntent.ListenItem(item), stack)
        assertEquals(stack, result)
    }

    @Test
    fun readItem_deduplicatesWhenAlreadyAtSameReader() {
        val item = epubItem()
        val stack = listOf(LibraryNav.Items, LibraryNav.Reader(item))
        val result = reduceNavStack(NavigationIntent.ReadItem(item), stack)
        assertEquals(stack, result)
    }

    @Test
    fun listenItemAtSec_deduplicatesWhenAlreadyAtSamePlayer() {
        val item = audioItem()
        val stack = listOf(LibraryNav.Items, LibraryNav.AudiobookPlayer(item))
        val result = reduceNavStack(NavigationIntent.ListenItemAtSec(item, 10.0), stack)
        assertEquals(stack, result)
    }
}
