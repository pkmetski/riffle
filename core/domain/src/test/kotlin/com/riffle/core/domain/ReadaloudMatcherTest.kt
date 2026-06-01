package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadaloudMatcherTest {

    @Test
    fun `single ABS candidate with matching ISBN-13 is Confirmed`() {
        val book = storytellerBook(isbn = "9780261103573")
        val abs = absItem(
            serverUuid = "abs-1",
            libraryItemId = "item-1",
            isbn = "9780261103573",
        )

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed(absServerUuid = "abs-1", absLibraryItemId = "item-1"), result)
    }

    @Test
    fun `ISBN match ignores hyphens and surrounding whitespace`() {
        val book = storytellerBook(isbn = " 978-0-261-10357-3 ")
        val abs = absItem(isbn = "9780261103573")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `single ABS candidate with matching ASIN is Confirmed`() {
        val book = storytellerBook(asin = "b000fc1pzc")
        val abs = absItem(asin = "B000FC1PZC")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `ISBN collision across candidates is Unmatched`() {
        val book = storytellerBook(isbn = "9780261103573")
        val a = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780261103573")
        val b = absItem(serverUuid = "abs-2", libraryItemId = "item-9", isbn = "9780261103573")

        val result = ReadaloudMatcher.match(book, listOf(a, b))

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `ASIN collision across candidates is Unmatched`() {
        val book = storytellerBook(asin = "B000FC1PZC")
        val a = absItem(serverUuid = "abs-1", libraryItemId = "item-1", asin = "B000FC1PZC")
        val b = absItem(serverUuid = "abs-2", libraryItemId = "item-9", asin = "B000FC1PZC")

        val result = ReadaloudMatcher.match(book, listOf(a, b))

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `no candidates is Unmatched`() {
        val book = storytellerBook(isbn = "9780261103573")

        val result = ReadaloudMatcher.match(book, emptyList())

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `exact normalised title and author is Confirmed when no identifiers present`() {
        val book = storytellerBook(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `title and author match is case-insensitive and whitespace-collapsed`() {
        val book = storytellerBook(title = "the   fellowship of THE ring", author = "j.r.r.   tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `title and author match strips punctuation`() {
        val book = storytellerBook(title = "The Fellowship of the Ring!", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring.", author = "J.R.R. Tolkien.")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `author name order variant Smith John equals John Smith`() {
        val book = storytellerBook(title = "Atomic Habits", author = "Clear, James")
        val abs = absItem(title = "Atomic Habits", author = "James Clear")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `title and author collision is Unmatched`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear")
        val a = absItem(serverUuid = "abs-1", libraryItemId = "item-1", title = "Atomic Habits", author = "James Clear")
        val b = absItem(serverUuid = "abs-2", libraryItemId = "item-9", title = "Atomic Habits", author = "James Clear")

        val result = ReadaloudMatcher.match(book, listOf(a, b))

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `differing author rules out title-only match`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear")
        val abs = absItem(title = "Atomic Habits", author = "Someone Else")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `ISBN tier wins over title-author tier when identifier is decisive`() {
        // Storyteller has ISBN that matches one candidate exactly;
        // a second candidate matches on title+author but not ISBN.
        // ISBN tier must Confirm the ISBN candidate.
        val book = storytellerBook(
            title = "Atomic Habits",
            author = "James Clear",
            isbn = "9780735211292",
        )
        val isbnHit = absItem(
            serverUuid = "abs-1",
            libraryItemId = "item-isbn",
            title = "Atomic Habits (Hardcover)",
            author = "Clear, James",
            isbn = "9780735211292",
        )
        val titleAuthorOnly = absItem(
            serverUuid = "abs-2",
            libraryItemId = "item-ta",
            title = "Atomic Habits",
            author = "James Clear",
        )

        val result = ReadaloudMatcher.match(book, listOf(isbnHit, titleAuthorOnly))

        assertEquals(MatchResult.Confirmed("abs-1", "item-isbn"), result)
    }

    @Test
    fun `ISBN collision does not fall through to title-author tier`() {
        // The asymmetric cost rule (ADR 0021): if the strongest tier collides,
        // we must NOT silently fall through to a weaker tier — that risks wrong matches.
        val book = storytellerBook(
            title = "Atomic Habits",
            author = "James Clear",
            isbn = "9780735211292",
        )
        val isbnHit1 = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780735211292")
        val isbnHit2 = absItem(serverUuid = "abs-2", libraryItemId = "item-2", isbn = "9780735211292")
        val titleAuthorElsewhere = absItem(
            serverUuid = "abs-3",
            libraryItemId = "item-3",
            title = "Atomic Habits",
            author = "James Clear",
        )

        val result = ReadaloudMatcher.match(book, listOf(isbnHit1, isbnHit2, titleAuthorElsewhere))

        assertEquals(MatchResult.Unmatched, result)
    }

    private fun storytellerBook(
        uuid: String = "st-1",
        title: String = "The Fellowship of the Ring",
        author: String = "J.R.R. Tolkien",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableStorytellerBook(
        uuid = uuid,
        title = title,
        author = author,
        isbn = isbn,
        asin = asin,
    )

    private fun absItem(
        serverUuid: String = "abs-1",
        libraryItemId: String = "item-1",
        title: String = "The Fellowship of the Ring",
        author: String = "J.R.R. Tolkien",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableAbsItem(
        serverUuid = serverUuid,
        libraryItemId = libraryItemId,
        title = title,
        author = author,
        isbn = isbn,
        asin = asin,
    )
}
