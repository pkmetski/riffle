package com.riffle.app.feature.library

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterFacetTest {

    private fun item(
        id: String = "1",
        author: String = "Brandon Sanderson",
        genres: List<String> = emptyList(),
        publishedYear: String? = null,
        language: String? = null,
    ) = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "T",
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        genres = genres,
        publishedYear = publishedYear,
        language = language,
    )

    @Test
    fun author_matches_a_single_author() {
        assertTrue(facetMatches(item(author = "Brandon Sanderson"), FacetType.AUTHOR, "Brandon Sanderson", emptySet()))
    }

    @Test
    fun author_matches_each_token_of_a_coauthored_book() {
        val coauthored = item(author = "Brandon Sanderson, Janci Patterson")
        assertTrue(facetMatches(coauthored, FacetType.AUTHOR, "Brandon Sanderson", emptySet()))
        assertTrue(facetMatches(coauthored, FacetType.AUTHOR, "Janci Patterson", emptySet()))
    }

    @Test
    fun author_does_not_partial_match() {
        // "Brandon" is not a full author token, so it must not match "Brandon Sanderson".
        assertFalse(facetMatches(item(author = "Brandon Sanderson"), FacetType.AUTHOR, "Brandon", emptySet()))
    }

    @Test
    fun genre_matches_membership() {
        val it = item(genres = listOf("Fantasy", "Epic"))
        assertTrue(facetMatches(it, FacetType.GENRE, "Epic", emptySet()))
        assertFalse(facetMatches(it, FacetType.GENRE, "Sci-Fi", emptySet()))
    }

    @Test
    fun year_and_language_match_exactly() {
        assertTrue(facetMatches(item(publishedYear = "2010"), FacetType.YEAR, "2010", emptySet()))
        assertFalse(facetMatches(item(publishedYear = "2010"), FacetType.YEAR, "2011", emptySet()))
        assertTrue(facetMatches(item(language = "English"), FacetType.LANGUAGE, "English", emptySet()))
        assertFalse(facetMatches(item(language = "English"), FacetType.LANGUAGE, "Spanish", emptySet()))
    }

    @Test
    fun readaloud_matches_linked_set_membership() {
        assertTrue(facetMatches(item(id = "a"), FacetType.READALOUD, "all", setOf("a", "b")))
        assertFalse(facetMatches(item(id = "c"), FacetType.READALOUD, "all", setOf("a", "b")))
    }

    @Test
    fun title_is_the_value_except_for_readaloud() {
        assertEquals("Brandon Sanderson", facetTitle(FacetType.AUTHOR, "Brandon Sanderson"))
        assertEquals("English", facetTitle(FacetType.LANGUAGE, "English"))
        assertEquals("Readalouds", facetTitle(FacetType.READALOUD, "all"))
    }
}
