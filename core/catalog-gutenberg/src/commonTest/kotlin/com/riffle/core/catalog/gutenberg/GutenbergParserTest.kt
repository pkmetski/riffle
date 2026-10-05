package com.riffle.core.catalog.gutenberg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved from jvmTest to commonTest so the pure-Kotlin parsing logic is verified on iOS as well.
 * No resource loading — fixture JSON is inlined so no JVM classloader is needed.
 */
class GutenbergParserTest {

    @Test
    fun parsesListingWithCountAndNextLink() {
        val listing = GutenbergParser.parseListing(BOOKS_PAGE_1)
        assertEquals(70000, listing.count)
        assertEquals("https://gutendex.com/books/?page=2", listing.next)
        assertEquals(3, listing.items.size)
    }

    @Test
    fun parsesBookWithCanonicalEpubMime() {
        val listing = GutenbergParser.parseListing(BOOKS_PAGE_1)
        val pride = listing.items.first { it.id == 1342L }
        assertEquals("Pride and Prejudice", pride.title)
        assertEquals(listOf("Austen, Jane"), pride.authors)
        assertEquals(listOf("en"), pride.languages)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", pride.epubUrl)
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            pride.coverUrl,
        )
        assertNotNull(pride.description)
        assertTrue(pride.subjects.isNotEmpty())
    }

    @Test
    fun parsesBookWhoseEpubMimeHasCharsetSuffix() {
        // Regression: Gutendex occasionally emits `application/epub+zip; charset=utf-8` as the
        // formats key. A strict exact-match lookup would drop that entry. The parser must fall
        // through to a prefix-based match.
        val listing = GutenbergParser.parseListing(BOOKS_PAGE_1)
        val frank = listing.items.first { it.id == 84L }
        assertEquals("https://www.gutenberg.org/ebooks/84.epub3.images", frank.epubUrl)
    }

    @Test
    fun booksWithoutEpubUrlParseCleanlyWithNullEpubUrl() {
        val listing = GutenbergParser.parseListing(BOOKS_PAGE_1)
        val html = listing.items.first { it.id == 999999L }
        assertNull(html.epubUrl)
    }

    @Test
    fun parsesSingleBookDetailResponseShape() {
        val summary = GutenbergParser.parseBook(BOOK_DETAIL)
        assertNotNull(summary)
        assertEquals(1342L, summary.id)
        assertEquals("Pride and Prejudice", summary.title)
    }

    @Test
    fun toleratesUnknownTopLevelFields() {
        val json = """
          { "count": 1, "next": null, "previous": null, "results": [ { "id": 1, "title": "X",
            "authors": [], "translators": [], "subjects": [], "bookshelves": [],
            "languages": ["en"], "copyright": false, "media_type": "Text", "formats": {},
            "download_count": 0, "brand_new_field": "should be ignored" } ] }
        """.trimIndent()
        val listing = GutenbergParser.parseListing(json)
        assertEquals(1, listing.items.size)
    }

    companion object {
        private val BOOKS_PAGE_1 = """
            {
              "count": 70000,
              "next": "https://gutendex.com/books/?page=2",
              "previous": null,
              "results": [
                {
                  "id": 1342,
                  "title": "Pride and Prejudice",
                  "authors": [
                    { "name": "Austen, Jane", "birth_year": 1775, "death_year": 1817 }
                  ],
                  "translators": [],
                  "subjects": [
                    "England -- Social life and customs -- 19th century -- Fiction",
                    "Love stories",
                    "Sisters -- Fiction"
                  ],
                  "bookshelves": ["Best Books Ever Listings"],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {
                    "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images",
                    "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                    "text/html": "https://www.gutenberg.org/ebooks/1342.html"
                  },
                  "download_count": 68901,
                  "summaries": [
                    "Pride and Prejudice, by Jane Austen, is a classic romance novel."
                  ]
                },
                {
                  "id": 84,
                  "title": "Frankenstein; Or, The Modern Prometheus",
                  "authors": [
                    { "name": "Shelley, Mary Wollstonecraft", "birth_year": 1797, "death_year": 1851 }
                  ],
                  "translators": [],
                  "subjects": ["Gothic fiction", "Horror tales"],
                  "bookshelves": ["Gothic Fiction"],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {
                    "application/epub+zip; charset=utf-8": "https://www.gutenberg.org/ebooks/84.epub3.images",
                    "image/jpeg": "https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"
                  },
                  "download_count": 55123,
                  "summaries": []
                },
                {
                  "id": 999999,
                  "title": "Missing EPUB Title",
                  "authors": [
                    { "name": "Doe, Unknown" }
                  ],
                  "translators": [],
                  "subjects": ["Test data"],
                  "bookshelves": [],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {
                    "text/html": "https://www.gutenberg.org/ebooks/999999.html"
                  },
                  "download_count": 0
                }
              ]
            }
        """.trimIndent()

        private val BOOK_DETAIL = """
            {
              "id": 1342,
              "title": "Pride and Prejudice",
              "authors": [
                { "name": "Austen, Jane", "birth_year": 1775, "death_year": 1817 }
              ],
              "translators": [],
              "subjects": [
                "England -- Social life and customs -- 19th century -- Fiction",
                "Love stories"
              ],
              "bookshelves": ["Best Books Ever Listings"],
              "languages": ["en"],
              "copyright": false,
              "media_type": "Text",
              "formats": {
                "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images",
                "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"
              },
              "download_count": 68901,
              "summaries": [
                "Pride and Prejudice, by Jane Austen, is a classic romance novel."
              ]
            }
        """.trimIndent()
    }
}
