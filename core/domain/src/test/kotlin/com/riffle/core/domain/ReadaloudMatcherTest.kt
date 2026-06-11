package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudMatcherTest {

    // ---- Tier 1: identifier ----------------------------------------------------------------

    @Test
    fun `single ABS candidate with matching ISBN-13 confirms it`() {
        val book = storytellerBook(isbn = "9780261103573")
        val abs = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780261103573")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `ISBN match ignores hyphens and surrounding whitespace`() {
        val book = storytellerBook(isbn = " 978-0-261-10357-3 ")
        val abs = absItem(isbn = "9780261103573")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `single ABS candidate with matching ASIN confirms it`() {
        val book = storytellerBook(asin = "b000fc1pzc")
        val abs = absItem(asin = "B000FC1PZC")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `multiple ABS candidates with the same ISBN all match`() {
        // 1:many semantics: the same readaloud legitimately links to every ABS item that
        // shares its identifier (e.g. ebook + audiobook entries in different libraries).
        val book = storytellerBook(isbn = "9780261103573")
        val a = absItem(serverUuid = "abs-1", libraryItemId = "ebook", isbn = "9780261103573")
        val b = absItem(serverUuid = "abs-2", libraryItemId = "audio", isbn = "9780261103573")

        val result = ReadaloudMatcher.match(book, listOf(a, b))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-2", "audio")), confirmedLinks(result))
    }

    @Test
    fun `ISBN tier wins over title-author tier when identifier is decisive`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear", isbn = "9780735211292")
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
            confirmed("abs-1" to "item-isbn"),
            ReadaloudMatcher.match(book, listOf(isbnHit, titleAuthorOnly)),
        )
    }

    @Test
    fun `Tier 1 hits suppress Tier 2 even when Tier 1 has multiple candidates`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear", isbn = "9780735211292")
        val isbnHit1 = absItem(serverUuid = "abs-1", libraryItemId = "item-1", isbn = "9780735211292")
        val isbnHit2 = absItem(serverUuid = "abs-2", libraryItemId = "item-2", isbn = "9780735211292")
        val titleAuthorElsewhere = absItem(
            serverUuid = "abs-3", libraryItemId = "item-3",
            title = "Atomic Habits", author = "James Clear",
        )

        val result = ReadaloudMatcher.match(book, listOf(isbnHit1, isbnHit2, titleAuthorElsewhere))

        assertEquals(setOf(Confirmed("abs-1", "item-1"), Confirmed("abs-2", "item-2")), confirmedLinks(result))
    }

    // ---- Tier 2: normalised title + author -------------------------------------------------

    @Test
    fun `multiple ABS candidates with matching title and author all match`() {
        val book = storytellerBook(title = "The Martian", author = "Andy Weir")
        val ebook = absItem(serverUuid = "abs-1", libraryItemId = "ebook", title = "The Martian", author = "Andy Weir")
        val audio = absItem(serverUuid = "abs-1", libraryItemId = "audio", title = "The Martian", author = "Andy Weir")

        val result = ReadaloudMatcher.match(book, listOf(ebook, audio))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-1", "audio")), confirmedLinks(result))
    }

    @Test
    fun `exact normalised title and author matches when no identifiers present`() {
        val book = storytellerBook(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `title and author match is case-insensitive and whitespace-collapsed`() {
        val book = storytellerBook(title = "the   fellowship of THE ring", author = "j.r.r.   tolkien")
        val abs = absItem(title = "The Fellowship of the Ring", author = "J.R.R. Tolkien")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `title and author match strips punctuation`() {
        val book = storytellerBook(title = "The Fellowship of the Ring!", author = "J.R.R. Tolkien")
        val abs = absItem(title = "The Fellowship of the Ring.", author = "J.R.R. Tolkien.")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `author name order variant Smith John equals John Smith`() {
        val book = storytellerBook(title = "Atomic Habits", author = "Clear, James")
        val abs = absItem(title = "Atomic Habits", author = "James Clear")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `subtitle after colon is stripped before title comparison`() {
        val book = storytellerBook(title = "The Martian: A Novel", author = "Andy Weir")
        val abs = absItem(title = "The Martian", author = "Andy Weir")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `subtitle after em-dash is stripped before title comparison`() {
        val book = storytellerBook(title = "Project Hail Mary — A Novel", author = "Andy Weir")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `colon that is part of the work name still matches the full title`() {
        // Real data: ABS "2001: A Space Odyssey" vs Storyteller "2001. A Space Odyssey". The
        // colon here is part of the title, not a "A Novel"-style subtitle, so stripping it to
        // "2001" loses everything that discriminates the book. The full forms align exactly.
        val book = storytellerBook(title = "2001. A Space Odyssey", author = "Arthur C. Clarke")
        val abs = absItem(title = "2001: A Space Odyssey", author = "Arthur C. Clarke")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `colon subtitle still matches when both sides carry it`() {
        // Stripping must remain available: "A Dance with Dragons: A Song of Ice and Fire: Book
        // Five" on the Storyteller side vs the bare ABS title still pairs via the head form.
        val book = storytellerBook(
            title = "A Dance with Dragons: A Song of Ice and Fire: Book Five",
            author = "George R.R. Martin",
        )
        val abs = absItem(title = "A Dance with Dragons", author = "George R.R. Martin")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `trailing format parenthetical on the ABS audiobook still matches`() {
        // Real data: Storyteller readaloud "The Hobbit" must link to its ABS audiobook entry
        // "The Hobbit (Dramatized)" as well as the bare-titled ebook. ABS tags audiobook
        // editions with a trailing "(Dramatized)"/"(Unabridged)"-style qualifier the Storyteller
        // side never carries; it is the audiobook analogue of a stripped "A Novel" subtitle.
        val book = storytellerBook(title = "The Hobbit", author = "J. R. R. Tolkien")
        val abs = absItem(title = "The Hobbit (Dramatized)", author = "J. R. R. Tolkien")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `ebook and parenthetical audiobook both match the same readaloud`() {
        // The whole point of the multiplicity model: one readaloud links to its ebook *and* its
        // audiobook, even though only the audiobook carries the format qualifier.
        val book = storytellerBook(title = "The Hobbit", author = "J. R. R. Tolkien")
        val ebook = absItem(serverUuid = "abs-1", libraryItemId = "ebook", title = "The Hobbit", author = "J. R. R. Tolkien")
        val audio = absItem(serverUuid = "abs-1", libraryItemId = "audio", title = "The Hobbit (Dramatized)", author = "J. R. R. Tolkien")

        val result = ReadaloudMatcher.match(book, listOf(ebook, audio))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-1", "audio")), confirmedLinks(result))
    }

    @Test
    fun `trailing parenthetical strips while a title-internal colon is preserved`() {
        // The qualifier must come off without the colon-head form swallowing the work name:
        // "2001: A Space Odyssey (Unabridged)" must still align with the full "2001: A Space
        // Odyssey", which only the parenthetical-stripped *full* form provides (the colon-head
        // collapses to "2001").
        val book = storytellerBook(title = "2001: A Space Odyssey", author = "Arthur C. Clarke")
        val abs = absItem(title = "2001: A Space Odyssey (Unabridged)", author = "Arthur C. Clarke")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `leading article on one side does not block title match`() {
        val book = storytellerBook(title = "The Dragons of Eden", author = "Carl Sagan")
        val abs = absItem(title = "Dragons of Eden", author = "Carl Sagan")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `Storyteller multi-author list still matches single-author ABS`() {
        val book = storytellerBook(
            title = "The Dragons of Eden",
            author = "Carl Sagan, JD Jackson, Ann Druyan",
        )
        val abs = absItem(title = "The Dragons of Eden", author = "Carl Sagan")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `Storyteller duplicated-author OPF still matches single-author ABS`() {
        val book = storytellerBook(title = "Project Hail Mary", author = "Andy Weir, Andy Weir;")
        val abs = absItem(title = "Project Hail Mary", author = "Andy Weir")

        assertEquals(confirmed("abs-1" to "item-1"), ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `middle initial plus an ABS-only co-author still matches the ebook`() {
        // Real data — "The Grand Design": the Storyteller readaloud lists the author both ways
        // ("Stephen Hawking" + "Stephen W. Hawking"), contributing a middle-initial "w" the ABS
        // ebook lacks, while the ABS ebook adds the curated co-author "Leonard Mlodinow" the
        // readaloud omits. Flatten-and-subset breaks both directions, so the ebook was dropped
        // and only the bare-{stephen,hawking} audiobook matched.
        val book = storytellerBook(title = "The Grand Design", author = "Stephen Hawking, Stephen W. Hawking")
        val ebook = absItem(serverUuid = "abs-1", libraryItemId = "ebook", title = "The Grand Design", author = "Stephen Hawking, Leonard Mlodinow")
        val audio = absItem(serverUuid = "abs-2", libraryItemId = "audio", title = "The Grand Design", author = "Stephen Hawking")

        val result = ReadaloudMatcher.match(book, listOf(ebook, audio))

        assertEquals(setOf(Confirmed("abs-1", "ebook"), Confirmed("abs-2", "audio")), confirmedLinks(result))
    }

    // ---- Tier 3: fuzzy → Pending Review ----------------------------------------------------

    @Test
    fun `fuzzy title above threshold with matching author surfaces a Pending candidate`() {
        // Titles differ by one token out of seven (Dice ≈ 0.857 ≥ 0.85); author identical.
        val book = storytellerBook(
            title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams",
        )
        val abs = absItem(
            title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams",
        )

        val result = ReadaloudMatcher.match(book, listOf(abs))

        assertTrue(result is MatchOutcome.PendingReview)
        val candidate = (result as MatchOutcome.PendingReview).candidates.single()
        assertEquals("abs-1", candidate.absServerUuid)
        assertEquals("item-1", candidate.absLibraryItemId)
        assertTrue("score ${candidate.score} should be >= threshold", candidate.score >= ReadaloudMatcher.FUZZY_THRESHOLD)
    }

    @Test
    fun `fuzzy ignores case punctuation and whitespace like the exact tiers`() {
        val book = storytellerBook(
            title = "the HITCHHIKERS  guide to the galaxy part one!!!", author = "douglas   adams",
        )
        val abs = absItem(
            title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams",
        )

        assertTrue(ReadaloudMatcher.match(book, listOf(abs)) is MatchOutcome.PendingReview)
    }

    @Test
    fun `fuzzy tolerates author name-order variant`() {
        val book = storytellerBook(
            title = "The Hitchhikers Guide to the Galaxy Part One", author = "Adams, Douglas",
        )
        val abs = absItem(
            title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams",
        )

        assertTrue(ReadaloudMatcher.match(book, listOf(abs)) is MatchOutcome.PendingReview)
    }

    @Test
    fun `multiple fuzzy candidates above threshold are surfaced together`() {
        val book = storytellerBook(
            title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams",
        )
        val abs2 = absItem(
            serverUuid = "abs-1", libraryItemId = "two",
            title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams",
        )
        val abs3 = absItem(
            serverUuid = "abs-2", libraryItemId = "three",
            title = "The Hitchhikers Guide to the Galaxy Part Three", author = "Douglas Adams",
        )

        val result = ReadaloudMatcher.match(book, listOf(abs2, abs3))

        assertTrue(result is MatchOutcome.PendingReview)
        assertEquals(
            setOf("abs-1" to "two", "abs-2" to "three"),
            (result as MatchOutcome.PendingReview).candidates
                .map { it.absServerUuid to it.absLibraryItemId }.toSet(),
        )
    }

    @Test
    fun `exact Tier 2 match wins over a fuzzy candidate`() {
        val book = storytellerBook(
            title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams",
        )
        val exact = absItem(
            serverUuid = "abs-1", libraryItemId = "exact",
            title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams",
        )
        val fuzzy = absItem(
            serverUuid = "abs-2", libraryItemId = "fuzzy",
            title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams",
        )

        assertEquals(confirmed("abs-1" to "exact"), ReadaloudMatcher.match(book, listOf(exact, fuzzy)))
    }

    // ---- Tier 4 / rejection ----------------------------------------------------------------

    @Test
    fun `title too different falls below threshold and is Unmatched`() {
        // Dice ≈ 0.667 on title (3 shared of 3 vs 6 tokens).
        val book = storytellerBook(title = "The Way of Kings", author = "Brandon Sanderson")
        val abs = absItem(
            title = "The Way of Kings Prime Extended Reader Edition", author = "Brandon Sanderson",
        )

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `matching title but author below threshold is Unmatched`() {
        val book = storytellerBook(title = "Atomic Habits", author = "Brandon Sanderson")
        val abs = absItem(title = "Atomic Habits", author = "Brandon Saunders")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `no candidates is Unmatched`() {
        val book = storytellerBook(isbn = "9780261103573")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, emptyList()))
    }

    @Test
    fun `subset match still rejects when no author token overlaps`() {
        val book = storytellerBook(title = "The Same Title", author = "Carl Sagan, JD Jackson")
        val abs = absItem(title = "The Same Title", author = "Someone Else")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `empty author on one side cannot satisfy subset rule`() {
        val book = storytellerBook(title = "X", author = "")
        val abs = absItem(title = "X", author = "Anyone")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `empty title is Unmatched even with a candidate`() {
        val book = storytellerBook(title = "", author = "Andy Weir")
        val abs = absItem(title = "The Martian", author = "Andy Weir")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    @Test
    fun `differing author rules out title-only match`() {
        val book = storytellerBook(title = "Atomic Habits", author = "James Clear")
        val abs = absItem(title = "Atomic Habits", author = "Someone Else")

        assertEquals(MatchOutcome.Unmatched, ReadaloudMatcher.match(book, listOf(abs)))
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun confirmed(vararg slots: Pair<String, String>) =
        MatchOutcome.Confirmed(slots.map { Confirmed(it.first, it.second) })

    private fun confirmedLinks(outcome: MatchOutcome): Set<Confirmed> =
        (outcome as MatchOutcome.Confirmed).links.toSet()

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
