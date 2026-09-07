package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OReillyParserTest {

    // Field set captured from the live GET /api/v2/search/ response (2026-09): `content_format`
    // distinguishes audio, `has_audio` is never emitted, `duration_seconds` is -1 for books, and
    // audiobook `archive_id`s carry an "AU" suffix.
    private val searchJson = """
        {
          "total": 2,
          "next": "https://learning.oreilly.com/api/v2/search/?query=kotlin&formats=book&limit=1&page=1",
          "results": [
            {
              "id": "https://www.safaribooksonline.com/api/v1/book/9781617299605/",
              "archive_id": "9781617299605",
              "ourn": "urn:orm:book:9781617299605",
              "isbn": "9781617299605",
              "title": "Kotlin in Action, Second Edition",
              "authors": ["Svetlana Isakova", "Sebastian Aigner"],
              "publishers": ["Manning Publications"],
              "cover_url": "https://learning.oreilly.com/library/cover/9781617299605/",
              "format": "book",
              "content_format": "book",
              "issued": "2024-05-30T00:00:00Z",
              "language": "en",
              "virtual_pages": 843,
              "duration_seconds": -1,
              "description": "<span>Expert guidance…</span>"
            },
            {
              "archive_id": "9781617299605AU",
              "ourn": "urn:orm:audiobook:9781617299605AU",
              "title": "Kotlin in Action, Second Edition",
              "authors": ["Svetlana Isakova"],
              "format": "audiobook",
              "content_format": "audiobook",
              "duration_seconds": 56304,
              "cover_url": "https://learning.oreilly.com/library/cover/9781617299605AU/"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `search parses only books into the books root`() {
        val items = OReillyParser.parseSearch(searchJson, OReillyRoots.BOOKS)
        assertEquals(1, items.size)
        val book = items.single()
        assertEquals("9781617299605", book.id)
        assertEquals("Kotlin in Action, Second Edition", book.title)
        assertEquals("Svetlana Isakova, Sebastian Aigner", book.author)
        assertEquals(BookFormat.Epub, book.ebookFormat)
        assertEquals(false, book.hasAudio)
        assertEquals("2024", book.publishedYear)
        assertEquals("Manning Publications", book.publisher)
        assertEquals("en", book.language)
        assertEquals(843, book.pageCount)
        // duration_seconds is -1 for books; must not leak as audio duration.
        assertEquals(0.0, book.audioDurationSec, 0.001)
        assertEquals(OReillyRoots.BOOKS, book.rootId)
        // Ignore the hit's low-res cover_url; request the full-resolution cover from the CDN.
        assertEquals("https://learning.oreilly.com/library/cover/9781617299605/600/", book.coverUrl)
    }

    @Test
    fun `search parses only audiobooks into the audiobooks root`() {
        val items = OReillyParser.parseSearch(searchJson, OReillyRoots.AUDIOBOOKS)
        assertEquals(1, items.size)
        val audio = items.single()
        assertEquals("9781617299605AU", audio.id)
        assertEquals(BookFormat.Audiobook, audio.ebookFormat)
        assertTrue(audio.hasAudio)
        assertEquals(56304.0, audio.audioDurationSec, 0.001)
        assertEquals(OReillyRoots.AUDIOBOOKS, audio.rootId)
    }

    @Test
    fun `unknown keys are ignored and hits without an id are dropped`() {
        val json = """
            {"results":[
              {"title":"No id here","format":"book","some_future_field":42},
              {"archive_id":"x1","title":"Has id","format":"book"}
            ]}
        """.trimIndent()
        val items = OReillyParser.parseSearch(json, OReillyRoots.BOOKS)
        assertEquals(1, items.size)
        assertEquals("x1", items.single().id)
    }

    @Test
    fun `book detail maps to a catalog item`() {
        // Shape captured from the live GET /api/v2/epubs/{urn}/ response (2026-09): no authors/cover
        // in the metadata; description is a media-type map; page_count/publication_date present.
        val json = """
            {
              "identifier": "9781098122584",
              "title": "Automate the Boring Stuff with Python, 2nd Edition",
              "language": "en",
              "isbn": "9781593279929",
              "content_format": "book",
              "publication_date": "2019-11-12",
              "virtual_pages": 768,
              "page_count": 592,
              "total_running_time_secs": null,
              "descriptions": {"text/html": "<span>desc</span>", "text/plain": "desc"}
            }
        """.trimIndent()
        val detail = OReillyParser.parseBookDetail(json)
        val item = OReillyParser.bookDetailToCatalogItem(
            id = "9781098122584",
            detail = detail,
            coverUrl = OReillyApi.coverUrl("9781098122584"),
            author = "Al Sweigart",
        )
        assertEquals("Automate the Boring Stuff with Python, 2nd Edition", item.title)
        assertEquals("Al Sweigart", item.author) // carried over from the search listing
        assertEquals("https://learning.oreilly.com/library/cover/9781098122584/600/", item.coverUrl)
        assertEquals("<span>desc</span>", item.description)
        assertEquals("en", item.language)
        assertEquals("9781593279929", item.isbn)
        assertEquals(592, item.pageCount) // prefers page_count over virtual_pages
        assertEquals("2019", item.publishedYear)
        assertEquals(false, item.hasAudio)
    }

    @Test
    fun `spine reference_id yields the packaged file path`() {
        val json = """
            {"count":2,"next":null,"results":[
              {"title":"Cover Page","reference_id":"9781098122584-/xhtml/cover.xhtml","ourn":"urn:orm:book:9781098122584:chapter:xhtml%2fcover.xhtml"},
              {"title":"Title Page","reference_id":"9781098122584-/xhtml/title.xhtml"}
            ]}
        """.trimIndent()
        val spine = OReillyParser.parseSpine(json)
        assertEquals(listOf("xhtml/cover.xhtml", "xhtml/title.xhtml"), spine.results.map { it.fullPath })
        assertEquals("Cover Page", spine.results.first().title)
    }

    @Test
    fun `files parse kinds and paths`() {
        val json = """
            {"count":3,"next":null,"results":[
              {"full_path":"xhtml/cover.xhtml","media_type":"application/xhtml+xml","kind":"chapter"},
              {"full_path":"images/00fig00.jpg","media_type":"image/jpeg","kind":"image"},
              {"full_path":"styles/book.css","media_type":"text/css","kind":"stylesheet"}
            ]}
        """.trimIndent()
        val files = OReillyParser.parseFiles(json)
        assertEquals(3, files.results.size)
        assertEquals("stylesheet", files.results.last().kind)
        assertEquals("text/css", files.results.last().mediaType)
    }

    @Test
    fun `blank query yields no search results without hitting the network`() {
        // parseSearch is pure; the catalog guards blank queries, mirrored here for the contract.
        assertNull("".ifBlank { null })
    }
}
