package com.riffle.app.feature.library

import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationSearchTest {

    private fun item(id: String, title: String) = LibraryItem(
        id = id,
        libraryId = "lib1",
        title = title,
        author = "Author",
        coverUrl = "https://cover/$id.jpg",
        sourceId = "srv1",
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = com.riffle.core.domain.EbookFormat.Epub,
    )

    private fun annotation(
        id: String,
        itemId: String,
        type: String = "HIGHLIGHT",
        note: String? = null,
        textSnippet: String = "",
        bookmarkTitle: String = "",
    ) = Annotation(
        id = id,
        sourceId = "srv1",
        itemId = itemId,
        type = type,
        cfi = "epubcfi(/6/4!/4)",
        color = "yellow",
        note = note,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "ch.html",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = bookmarkTitle,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private val items = listOf(item("b1", "Children of Dune"), item("b2", "Dune Messiah"))

    @Test
    fun blankQueryReturnsNothing() {
        val annos = listOf(annotation("a1", "b1", textSnippet = "conscience"))
        assertEquals(emptyList<AnnotationSearchResult>(), searchAnnotations(annos, items, "   "))
    }

    @Test
    fun matchesNoteSnippetAndBookmarkTitleCaseInsensitively() {
        val annos = listOf(
            annotation("a1", "b1", textSnippet = "The CONSCIENCE of the king"),
            annotation("a2", "b1", note = "about conscience"),
            annotation("a3", "b2", type = "BOOKMARK", bookmarkTitle = "Conscience chapter"),
            annotation("a4", "b1", textSnippet = "unrelated passage"),
        )
        val result = searchAnnotations(annos, items, "conscience").map { it.annotation.id }
        assertEquals(listOf("a1", "a2", "a3"), result)
    }

    @Test
    fun scopesToLibraryItemsOnly() {
        val annos = listOf(
            annotation("a1", "b1", textSnippet = "conscience"),
            annotation("aX", "OTHER_LIBRARY_ITEM", textSnippet = "conscience"),
        )
        val result = searchAnnotations(annos, items, "conscience").map { it.annotation.id }
        assertEquals(listOf("a1"), result)
    }

    @Test
    fun carriesBookTitleAndCover() {
        val annos = listOf(annotation("a1", "b1", textSnippet = "conscience"))
        val r = searchAnnotations(annos, items, "conscience").single()
        assertEquals("Children of Dune", r.bookTitle)
        assertEquals("https://cover/b1.jpg", r.bookCoverUrl)
    }

    // --- audiobook bookmark search ---

    private fun audiobookBookmark(id: String, itemId: String, title: String) =
        AudiobookBookmark(id = id, sourceId = "srv1", itemId = itemId, positionSec = 0.0, title = title, createdAt = 0L)

    @Test
    fun audiobookBookmarkBlankQueryReturnsNothing() {
        val bms = listOf(audiobookBookmark("bm1", "b1", "The Battle of Winterfell"))
        assertEquals(emptyList<AudiobookBookmarkSearchResult>(), searchAudiobookBookmarks(bms, items, "   "))
    }

    @Test
    fun audiobookBookmarkMatchesTitleCaseInsensitively() {
        val bms = listOf(
            audiobookBookmark("bm1", "b1", "The BATTLE of Winterfell"),
            audiobookBookmark("bm2", "b1", "Chapter Three"),
        )
        val result = searchAudiobookBookmarks(bms, items, "battle").map { it.bookmark.id }
        assertEquals(listOf("bm1"), result)
    }

    @Test
    fun audiobookBookmarkScopesToLibraryItems() {
        val bms = listOf(
            audiobookBookmark("bm1", "b1", "found"),
            audiobookBookmark("bm2", "UNKNOWN_ITEM", "found"),
        )
        val result = searchAudiobookBookmarks(bms, items, "found").map { it.bookmark.id }
        assertEquals(listOf("bm1"), result)
    }

    @Test
    fun audiobookBookmarkCarriesBookTitleAndCover() {
        val bms = listOf(audiobookBookmark("bm1", "b1", "My Marker"))
        val r = searchAudiobookBookmarks(bms, items, "marker").single()
        assertEquals("Children of Dune", r.bookTitle)
        assertEquals("https://cover/b1.jpg", r.bookCoverUrl)
    }

    @Test
    fun unnamedBookmarkFoundByTextSnippet() {
        val annos = listOf(
            annotation("bm1", "b1", type = "BOOKMARK", textSnippet = "the conscience of the king", bookmarkTitle = ""),
            annotation("bm2", "b1", type = "BOOKMARK", textSnippet = "unrelated passage", bookmarkTitle = ""),
        )
        val result = searchAnnotations(annos, items, "conscience").map { it.annotation.id }
        assertEquals(listOf("bm1"), result)
    }
}
