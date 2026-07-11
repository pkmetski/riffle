package com.riffle.core.catalog.gutenberg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergParserTest {

    private fun loadFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `parses listing with count and next link`() {
        val listing = GutenbergParser.parseListing(loadFixture("gutendex-books-page-1.json"))
        assertEquals(70000, listing.count)
        assertEquals("https://gutendex.com/books/?page=2", listing.next)
        assertEquals(3, listing.items.size)
    }

    @Test
    fun `parses a book with the canonical EPUB MIME`() {
        val listing = GutenbergParser.parseListing(loadFixture("gutendex-books-page-1.json"))
        val pride = listing.items.first { it.id == 1342L }
        assertEquals("Pride and Prejudice", pride.title)
        assertEquals(listOf("Austen, Jane"), pride.authors)
        assertEquals(listOf("en"), pride.languages)
        assertEquals(
            "https://www.gutenberg.org/ebooks/1342.epub3.images",
            pride.epubUrl,
        )
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            pride.coverUrl,
        )
        assertNotNull(pride.description)
        assertTrue(pride.subjects.isNotEmpty())
    }

    @Test
    fun `parses a book whose EPUB MIME has a charset suffix`() {
        // Regression: Gutendex occasionally emits `application/epub+zip; charset=utf-8` as the
        // formats key. A strict exact-match lookup on `application/epub+zip` would drop that
        // entry and downgrade the item to no-download. The parser must fall through to a
        // prefix-based match.
        val listing = GutenbergParser.parseListing(loadFixture("gutendex-books-page-1.json"))
        val frank = listing.items.first { it.id == 84L }
        assertEquals(
            "https://www.gutenberg.org/ebooks/84.epub3.images",
            frank.epubUrl,
        )
    }

    @Test
    fun `books without an EPUB URL parse cleanly with a null epubUrl`() {
        val listing = GutenbergParser.parseListing(loadFixture("gutendex-books-page-1.json"))
        val html = listing.items.first { it.id == 999999L }
        assertNull(html.epubUrl)
    }

    @Test
    fun `parses the single-book detail response shape`() {
        val summary = GutenbergParser.parseBook(loadFixture("gutendex-book-detail.json"))
        assertNotNull(summary)
        assertEquals(1342L, summary!!.id)
        assertEquals("Pride and Prejudice", summary.title)
    }

    @Test
    fun `tolerates unknown top-level fields`() {
        // Guard against Gutendex growing new keys we don't read yet — the parser must not fail
        // the whole response.
        val json = """
          { "count": 1, "next": null, "previous": null, "results": [ { "id": 1, "title": "X",
            "authors": [], "translators": [], "subjects": [], "bookshelves": [],
            "languages": ["en"], "copyright": false, "media_type": "Text", "formats": {},
            "download_count": 0, "brand_new_field": "should be ignored" } ] }
        """.trimIndent()
        val listing = GutenbergParser.parseListing(json)
        assertEquals(1, listing.items.size)
    }
}
