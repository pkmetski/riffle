package com.riffle.core.data

import com.riffle.core.network.NetworkStorytellerBook
import org.junit.Assert.assertEquals
import org.junit.Test

class StorytellerReadaloudSyncerTest {
    @Test fun `mapping builds entities preserving identifiers and local progress`() {
        val books = listOf(
            NetworkStorytellerBook(id = 42L, title = "Dune", authors = listOf("Frank Herbert", "Brian Herbert"), isbn = "111", asin = "B01"),
        )
        val entities = storytellerBooksToEntities(
            books = books,
            libraryId = "readaloud:st-1",
            coverUrlOf = { id -> "http://s/api/books/$id/cover" },
            lastOpenedAtMap = mapOf("42" to 1234L),
            progressMap = mapOf("42" to 0.5f),
        )
        assertEquals(1, entities.size)
        val e = entities[0]
        assertEquals("42", e.id)
        assertEquals("readaloud:st-1", e.libraryId)
        assertEquals("Dune", e.title)
        assertEquals("Frank Herbert, Brian Herbert", e.author)
        assertEquals("111", e.isbn)
        assertEquals("B01", e.asin)
        assertEquals("http://s/api/books/42/cover", e.coverUrl)
        assertEquals(0.5f, e.readingProgress)
        assertEquals(1234L, e.lastOpenedAt)
    }
}
