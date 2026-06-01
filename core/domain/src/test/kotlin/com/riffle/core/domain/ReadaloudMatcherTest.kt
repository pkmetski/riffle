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
    fun `subtitle after colon is stripped before title comparison`() {
        // Real-world case: Storyteller takes its title from the EPUB OPF and keeps the
        // "A Novel"-style subtitle; ABS users almost always curate that out.
        val book = storytellerBook(title = "The Martian: A Novel", author = "Andy Weir")
        val abs = absItem(title = "The Martian", author = "Andy Weir")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `subtitle after em-dash is stripped before title comparison`() {
        val book = storytellerBook(title = "Project Hail Mary — A Novel", author = "Andy Weir")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `leading article on one side does not block title match`() {
        // Library cataloguing convention drops leading "The"/"A"/"An"; OPF metadata usually
        // keeps it. Real-world Storyteller "The Dragons of Eden" must pair with ABS-curated
        // "Dragons of Eden".
        val book = storytellerBook(title = "The Dragons of Eden", author = "Carl Sagan")
        val abs = absItem(title = "Dragons of Eden", author = "Carl Sagan")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `Storyteller multi-author list still matches single-author ABS`() {
        // Storyteller's OPF-derived authors include narrators and foreword authors,
        // tagged role:"aut" indiscriminately. The matcher treats the ABS side's primary
        // author as a subset of the Storyteller side and Confirms.
        val book = storytellerBook(
            title = "The Dragons of Eden",
            author = "Carl Sagan, JD Jackson, Ann Druyan",
        )
        val abs = absItem(title = "The Dragons of Eden", author = "Carl Sagan")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `Storyteller duplicated-author OPF still matches single-author ABS`() {
        // Real-world data quality: Storyteller exports both "Andy Weir" and "Andy Weir;"
        // when the EPUB OPF has two <dc:creator> entries. After punctuation stripping
        // the token sets are equal.
        val book = storytellerBook(title = "Project Hail Mary", author = "Andy Weir, Andy Weir;")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Confirmed("abs-1", "item-1"), result)
    }

    @Test
    fun `subset match still rejects when no author token overlaps`() {
        val book = storytellerBook(title = "The Same Title", author = "Carl Sagan, JD Jackson")
        val abs = absItem(title = "The Same Title", author = "Someone Else")

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertEquals(MatchResult.Unmatched, result)
    }

    @Test
    fun `empty author on one side cannot satisfy subset rule`() {
        val book = storytellerBook(title = "X", author = "")
        val abs = absItem(title = "X", author = "Anyone")

        val result = ReadaloudMatcher.match(book, listOf(abs))

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
