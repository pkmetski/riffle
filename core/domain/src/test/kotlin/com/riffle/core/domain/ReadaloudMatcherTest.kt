package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudMatcherTest {

    @Test
    fun `single ABS candidate with matching ISBN-13 confirms it`() {
        val book = storytellerBook(isbn = "9780261103573")
        val abs = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780261103573")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `ISBN match ignores hyphens and surrounding whitespace`() {
        val book = storytellerBook(isbn = " 978-0-261-10357-3 ")
        val abs = absItem(isbn = "9780261103573")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `single ABS candidate with matching ASIN confirms it`() {
        val book = storytellerBook(asin = "b000fc1pzc")
        val abs = absItem(asin = "B000FC1PZC")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `multiple ABS candidates with the same ISBN all match`() {
        // 1:many semantics: the same readaloud legitimately links to every ABS item that
        // shares its identifier (e.g. ebook + audiobook entries in different libraries).
        val book = storytellerBook(isbn = "9780261103573")
        val a = absItem(serverUuid = "abs-1", libraryItemId = "ebook", isbn = "9780261103573")
        val b = absItem(serverUuid = "abs-2", libraryItemId = "audio", isbn = "9780261103573")

        val result = ReadaloudMatcher.match(book, listOf(a, b))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-2", "audio")), result.toSet())
    }

    @Test
    fun `multiple ABS candidates with matching title and author all match`() {
        val book = storytellerBook(title = "The Martian", author = "Andy Weir")
        val ebook = absItem(serverUuid = "abs-1", libraryItemId = "ebook", title = "The Martian", author = "Andy Weir")
        val audio = absItem(serverUuid = "abs-1", libraryItemId = "audio", title = "The Martian", author = "Andy Weir")

        val result = ReadaloudMatcher.match(book, listOf(ebook, audio))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-1", "audio")), result.toSet())
    }

    @Test
    fun `no candidates returns empty list`() {
        val book = storytellerBook(isbn = "9780261103573")

        assertTrue(ReadaloudMatcher.match(book, emptyList()).isEmpty())
    }

    @Test
    fun `exact normalised title and author matches when no identifiers present`() {
        val book = storytellerBook(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `title and author match is case-insensitive and whitespace-collapsed`() {
        val book = storytellerBook(title = "the   fellowship of THE ring", author = "j.r.r.   tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `title and author match strips punctuation`() {
        val book = storytellerBook(title = "The Fellowship of the Ring!", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring.", author = "J.R.R. Tolkien.")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `author name order variant Smith John equals John Smith`() {
        val book = storytellerBook(title = "Atomic Habits", author = "Clear, James")
        val abs = absItem(title = "Atomic Habits", author = "James Clear")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `subtitle after colon is stripped before title comparison`() {
        val book = storytellerBook(title = "The Martian: A Novel", author = "Andy Weir")
        val abs = absItem(title = "The Martian", author = "Andy Weir")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `subtitle after em-dash is stripped before title comparison`() {
        val book = storytellerBook(title = "Project Hail Mary — A Novel", author = "Andy Weir")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `leading article on one side does not block title match`() {
        val book = storytellerBook(title = "The Dragons of Eden", author = "Carl Sagan")
        val abs = absItem(title = "Dragons of Eden", author = "Carl Sagan")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `Storyteller multi-author list still matches single-author ABS`() {
        val book = storytellerBook(
            title = "The Dragons of Eden",
            author = "Carl Sagan, JD Jackson, Ann Druyan",
        )
        val abs = absItem(title = "The Dragons of Eden", author = "Carl Sagan")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `Storyteller duplicated-author OPF still matches single-author ABS`() {
        val book = storytellerBook(title = "Project Hail Mary", author = "Andy Weir, Andy Weir;")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        assertEquals(listOf(Confirmed("abs-1", "item-1")), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `subset match still rejects when no author token overlaps`() {
        val book = storytellerBook(title = "The Same Title", author = "Carl Sagan, JD Jackson")
        val abs = absItem(title = "The Same Title", author = "Someone Else")

        assertTrue(ReadaloudMatcher.match(book, listOf(abs)).isEmpty())
    }

    @Test
    fun `empty author on one side cannot satisfy subset rule`() {
        val book = storytellerBook(title = "X", author = "")
        val abs = absItem(title = "X", author = "Anyone")

        assertTrue(ReadaloudMatcher.match(book, listOf(abs)).isEmpty())
    }

    @Test
    fun `differing author rules out title-only match`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear")
        val abs = absItem(title = "Atomic Habits", author = "Someone Else")

        assertTrue(ReadaloudMatcher.match(book, listOf(abs)).isEmpty())
    }

    @Test
    fun `ISBN tier wins over title-author tier when identifier is decisive`() {
        // Storyteller has ISBN that matches one candidate exactly. A second candidate matches
        // only on title+author. Tier 1 takes precedence — only the identifier hit is returned.
        val book = storytellerBook(
            title = "Atomic Habits", author = "James Clear", isbn = "9780735211292",
        )
        val isbnHit = absItem(
            serverUuid = "abs-1", libraryItemId = "item-isbn",
            title = "Atomic Habits (Hardcover)", author = "Clear, James",
            isbn = "9780735211292",
        )
        val titleAuthorOnly = absItem(
            serverUuid = "abs-2", libraryItemId = "item-ta",
            title = "Atomic Habits", author = "James Clear",
        )

        assertEquals(
            listOf(Confirmed("abs-1", "item-isbn")),
            ReadaloudMatcher.match(book, listOf(isbnHit, titleAuthorOnly)),
        )
    }

    @Test
    fun `Tier 1 hits suppress Tier 2 even when Tier 1 has multiple candidates`() {
        // 1:many version of the asymmetric-cost rule: if any Tier 1 candidate matches,
        // we use ONLY Tier 1 hits and never let weaker Tier 2 candidates leak in.
        val book = storytellerBook(
            title = "Atomic Habits", author = "James Clear", isbn = "9780735211292",
        )
        val isbnHit1 = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780735211292")
        val isbnHit2 = absItem(serverUuid = "abs-2", libraryItemId = "item-2", isbn = "9780735211292")
        val titleAuthorElsewhere = absItem(
            serverUuid = "abs-3", libraryItemId = "item-3",
            title = "Atomic Habits", author = "James Clear",
        )

        val result = ReadaloudMatcher.match(book, listOf(isbnHit1, isbnHit2, titleAuthorElsewhere))

        assertEquals(
            setOf(Confirmed("abs-1", "item-1"), Confirmed("abs-2", "item-2")),
            result.toSet(),
        )
    }

    private fun storytellerBook(
        uuid: String = "st-1",
        title: String = "The Fellowship of the Ring",
        author: String = "J.R.R. Tolkien",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableStorytellerBook(uuid, title, author, isbn, asin)

    private fun absItem(
        serverUuid: String = "abs-1",
        libraryItemId: String = "item-1",
        title: String = "The Fellowship of the Ring",
        author: String = "J.R.R. Tolkien",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableAbsItem(serverUuid, libraryItemId, title, author, isbn, asin)
}
