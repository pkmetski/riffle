package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.BookFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OReillyReadingHistoryTest {

    private val historyJson = """
        {
          "count": 3,
          "next": null,
          "results": [
            {
              "archive_id": "9781491910368",
              "ourn": "urn:orm:book:9781491910368",
              "title": "Python Crash Course",
              "authors": ["Eric Matthes"],
              "content_type": "book",
              "format": "book",
              "percent_complete": 45.0,
              "last_read_date": "2026-10-15T10:30:00Z",
              "cover_url": "https://learning.oreilly.com/library/cover/9781491910368/"
            },
            {
              "archive_id": "9781492032649",
              "ourn": "urn:orm:book:9781492032649",
              "title": "Designing Data-Intensive Applications",
              "authors": ["Martin Kleppmann"],
              "content_type": "book",
              "format": "book",
              "percent_complete": 96.5,
              "last_read_date": "2026-10-10T08:00:00Z"
            },
            {
              "archive_id": "9781663721174AU",
              "ourn": "urn:orm:audiobook:9781663721174AU",
              "title": "Some Audiobook",
              "authors": ["Author One", "Author Two"],
              "content_type": "audiobook",
              "format": "audiobook",
              "percent_complete": 72.0,
              "last_read_date": "2026-10-12T14:00:00Z",
              "cover_url": "https://learning.oreilly.com/library/cover/9781663721174AU/"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parseUserProfile() {
        val json = """{"uuid":"abc-123","email":"user@example.com","first_name":"John"}"""
        val profile = OReillyParser.parseUserProfile(json)
        assertEquals("abc-123", profile.uuid)
        assertEquals("user@example.com", profile.email)
    }

    @Test
    fun parseUserProfileIgnoresUnknownKeys() {
        val json = """{"uuid":"xyz","future_field":42}"""
        val profile = OReillyParser.parseUserProfile(json)
        assertEquals("xyz", profile.uuid)
    }

    @Test
    fun parseReadingHistoryCount() {
        val response = OReillyParser.parseReadingHistory(historyJson)
        assertEquals(3, response.count)
        assertEquals(3, response.results.size)
    }

    @Test
    fun parseReadingHistoryBookItem() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[0]
        assertEquals("9781491910368", item.archiveId)
        assertEquals("Python Crash Course", item.title)
        assertEquals(listOf("Eric Matthes"), item.authors)
        assertEquals(45.0, item.percentComplete)
        assertEquals("book", item.format)
        assertEquals(false, item.isAudiobook)
    }

    @Test
    fun parseReadingHistoryAudiobookItem() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[2]
        assertEquals("9781663721174AU", item.archiveId)
        assertTrue(item.isAudiobook)
    }

    @Test
    fun readingHistoryItemToCatalogItemMapsProgressToFraction() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[0]
        val catalogItem = OReillyParser.readingHistoryItemToCatalogItem(item)!!
        assertEquals("9781491910368", catalogItem.id)
        assertEquals("Python Crash Course", catalogItem.title)
        assertEquals("Eric Matthes", catalogItem.author)
        assertEquals(BookFormat.Epub, catalogItem.ebookFormat)
        assertEquals(false, catalogItem.hasAudio)
        assertEquals(OReillyRoots.BOOKS, catalogItem.rootId)
        assertEquals(0.45f, catalogItem.readingProgress!!, 0.001f)
    }

    @Test
    fun readingHistoryItemUsesProvidedCoverUrl() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[0]
        val catalogItem = OReillyParser.readingHistoryItemToCatalogItem(item)!!
        assertEquals("https://learning.oreilly.com/library/cover/9781491910368/", catalogItem.coverUrl)
    }

    @Test
    fun readingHistoryItemFallsBackToHighResCoverWhenCoverUrlAbsent() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[1]
        assertNull(item.coverUrl)
        val catalogItem = OReillyParser.readingHistoryItemToCatalogItem(item)!!
        assertEquals("https://learning.oreilly.com/library/cover/9781492032649/600/", catalogItem.coverUrl)
    }

    @Test
    fun readingHistoryItemToCatalogItemReturnsNullWhenProgressAbsent() {
        val json = """
            {"count":1,"results":[
              {"archive_id":"9781234567890","title":"No Progress","format":"book","percent_complete":null}
            ]}
        """.trimIndent()
        val item = OReillyParser.parseReadingHistory(json).results[0]
        assertNull(OReillyParser.readingHistoryItemToCatalogItem(item))
    }

    @Test
    fun readingHistoryItemToCatalogItemReturnsNullWhenProgressIsZero() {
        val json = """
            {"count":1,"results":[
              {"archive_id":"9781234567890","title":"Not Started","format":"book","percent_complete":0.0}
            ]}
        """.trimIndent()
        val item = OReillyParser.parseReadingHistory(json).results[0]
        assertNull(OReillyParser.readingHistoryItemToCatalogItem(item))
    }

    @Test
    fun readingHistoryItemToCatalogItemReturnsNullWhenIdAbsent() {
        val json = """
            {"count":1,"results":[
              {"title":"No ID","format":"book","percent_complete":50.0}
            ]}
        """.trimIndent()
        val item = OReillyParser.parseReadingHistory(json).results[0]
        assertNull(OReillyParser.readingHistoryItemToCatalogItem(item))
    }

    @Test
    fun readingHistoryAudiobookMapsToAudiobookRoot() {
        val item = OReillyParser.parseReadingHistory(historyJson).results[2]
        val catalogItem = OReillyParser.readingHistoryItemToCatalogItem(item)!!
        assertEquals(OReillyRoots.AUDIOBOOKS, catalogItem.rootId)
        assertEquals(BookFormat.Audiobook, catalogItem.ebookFormat)
        assertTrue(catalogItem.hasAudio)
        assertEquals(0.72f, catalogItem.readingProgress!!, 0.001f)
    }

    @Test
    fun completedItemProgressClampedToOne() {
        val json = """
            {"count":1,"results":[
              {"archive_id":"id1","title":"T","format":"book","percent_complete":103.0}
            ]}
        """.trimIndent()
        val item = OReillyParser.parseReadingHistory(json).results[0]
        val catalogItem = OReillyParser.readingHistoryItemToCatalogItem(item)!!
        assertEquals(1.0f, catalogItem.readingProgress!!, 0.001f)
    }
}
