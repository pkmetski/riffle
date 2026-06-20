package com.riffle.app.feature.library

import com.riffle.core.domain.Annotation
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
        serverId = "srv1",
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
        serverId = "srv1",
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
}
