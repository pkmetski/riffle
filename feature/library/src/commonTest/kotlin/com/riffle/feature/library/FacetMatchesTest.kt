package com.riffle.feature.library

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FacetMatchesTest {

    private fun item(
        author: String = "",
        genres: List<String> = emptyList(),
        publishedYear: String? = null,
        language: String? = null,
        id: String = "id1",
    ) = LibraryItem(
        id = id,
        libraryId = "lib1",
        title = "Title",
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        publishedYear = publishedYear,
        genres = genres,
        language = language,
    )

    @Test
    fun authorFacetMatchesSingleAuthor() {
        val i = item(author = "Jane Austen")
        assertTrue(facetMatches(i, FacetType.AUTHOR, "Jane Austen", emptySet()))
    }

    @Test
    fun authorFacetMatchesMultipleAuthors() {
        val i = item(author = "Jane Austen, Charlotte Brontë")
        assertTrue(facetMatches(i, FacetType.AUTHOR, "Charlotte Brontë", emptySet()))
        assertFalse(facetMatches(i, FacetType.AUTHOR, "Charlotte", emptySet()))
    }

    @Test
    fun genreFacetMatchesContainedGenre() {
        val i = item(genres = listOf("Fantasy", "Adventure"))
        assertTrue(facetMatches(i, FacetType.GENRE, "Fantasy", emptySet()))
        assertFalse(facetMatches(i, FacetType.GENRE, "Horror", emptySet()))
    }

    @Test
    fun yearFacetMatchesExactYear() {
        val i = item(publishedYear = "1813")
        assertTrue(facetMatches(i, FacetType.YEAR, "1813", emptySet()))
        assertFalse(facetMatches(i, FacetType.YEAR, "1814", emptySet()))
    }

    @Test
    fun languageFacetMatchesExact() {
        val i = item(language = "en")
        assertTrue(facetMatches(i, FacetType.LANGUAGE, "en", emptySet()))
        assertFalse(facetMatches(i, FacetType.LANGUAGE, "fr", emptySet()))
    }

    @Test
    fun readaloudFacetMatchesWhenIdInLinkedSet() {
        val i = item(id = "book-42")
        assertTrue(facetMatches(i, FacetType.READALOUD, "", setOf("book-42")))
        assertFalse(facetMatches(i, FacetType.READALOUD, "", setOf("book-99")))
    }
}
