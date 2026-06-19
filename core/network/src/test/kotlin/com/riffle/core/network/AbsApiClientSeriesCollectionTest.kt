package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.riffle.core.domain.EbookFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientSeriesCollectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── getSeries ────────────────────────────────────────────────────────────

    @Test
    fun `getSeries parses series list from API response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[{"id":"ser-1","name":"My Series","libraryId":"lib-1","books":[""" +
                    """{"id":"item-1","libraryId":"lib-1","seriesSequence":"1","media":{"metadata":{"title":"Book One","authorName":"Author A"},"ebookFormat":"epub"}}""" +
                    """]}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        assertTrue(result is NetworkSeriesResult.Success)
        val success = result as NetworkSeriesResult.Success
        assertEquals(1, success.series.size)
        assertEquals("ser-1", success.series[0].id)
        assertEquals("My Series", success.series[0].name)
        assertEquals("lib-1", success.series[0].libraryId)
        assertEquals(1, success.series[0].bookCount)
    }

    @Test
    fun `getSeries parses items with sequence and progress`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[{"id":"ser-1","name":"S","libraryId":"lib-1","books":[""" +
                    """{"id":"item-1","libraryId":"lib-1","seriesSequence":"2","media":{"metadata":{"title":"B","authorName":"A"},"ebookFormat":"epub"},"userMediaProgress":{"progress":0.3,"ebookProgress":0.75}},""" +
                    """{"id":"item-2","libraryId":"lib-1","seriesSequence":null,"media":{"metadata":{"title":"C","authorName":"A"}}}""" +
                    """]}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val items = (result as NetworkSeriesResult.Success).series[0].items
        assertEquals(2, items.size)
        assertEquals("2", items[0].sequence)
        assertEquals(0.75f, items[0].readingProgress!!, 0.001f)
        assertEquals(EbookFormat.Epub, items[0].ebookFormat)
        assertNull(items[1].sequence)
        assertNull(items[1].readingProgress)
        assertEquals(EbookFormat.Unsupported, items[1].ebookFormat)
    }

    @Test
    fun `getSeries sends correct path and auth header`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"results":[]}""")
                .addHeader("Content-Type", "application/json")
        )
        client.getSeries(server.url("/").toString().trimEnd('/'), "lib-99", "tok-xyz", false)
        val request = server.takeRequest()
        assertEquals("Bearer tok-xyz", request.getHeader("Authorization"))
        assertEquals("/api/libraries/lib-99/series?limit=500", request.path)
    }

    @Test
    fun `getSeries parses series when libraryId is absent from series object`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"ser-1","name":"No LibId Series","books":[]}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        assertTrue(result is NetworkSeriesResult.Success)
        val series = (result as NetworkSeriesResult.Success).series
        assertEquals(1, series.size)
        assertEquals("lib-1", series[0].libraryId)
    }

    @Test
    fun `getSeries parses real server response shape`() = runTest {
        // JSON captured from a real Audiobookshelf server (minified=1, one series, one book)
        val realJson = """{"results":[{"id":"6824c42f-856b-409c-b32e-ad4a472f7447","name":"Discworld","nameIgnorePrefix":"Discworld","description":null,"addedAt":1706605805384,"updatedAt":1706605805384,"libraryId":"e77c113d-4383-488d-956f-89c18db431ac","books":[{"id":"3567e8b4-bed8-442f-9733-5dd642295462","ino":"1511850","oldLibraryItemId":null,"libraryId":"e77c113d-4383-488d-956f-89c18db431ac","folderId":"a4f7da01-fa75-45bf-96e5-693df9b6e6a6","path":"/books/Terry Pratchett/The Colour of Magic","relPath":"Terry Pratchett/The Colour of Magic","isFile":false,"mtimeMs":1708371028869,"ctimeMs":1762849628483,"birthtimeMs":0,"addedAt":1708369906982,"updatedAt":1762902014957,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"id":"8e4da48f-e0aa-4dd0-813a-9e2c15da892b","metadata":{"title":"The Colour Of Magic","titleIgnorePrefix":"Colour Of Magic, The","subtitle":null,"authorName":"Terry Pratchett","authorNameLF":"Pratchett, Terry","narratorName":"","seriesName":"Discworld #1","genres":["Fiction"],"publishedYear":"1983","publishedDate":null,"publisher":"Random House","description":"description text","isbn":"9781407034379","asin":null,"language":"English","explicit":false,"abridged":false},"coverPath":"/books/cover.jpg","tags":[],"numTracks":0,"numAudioFiles":0,"numChapters":0,"duration":0,"size":1001112,"ebookFormat":"epub"},"numFiles":3,"size":1177590}]}],"total":26,"limit":1,"page":0,"sortDesc":false,"minified":true,"include":""}"""
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(realJson)
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "e77c113d-4383-488d-956f-89c18db431ac", "token", false)
        assertTrue("Expected Success but got: $result", result is NetworkSeriesResult.Success)
        val series = (result as NetworkSeriesResult.Success).series
        assertEquals(1, series.size)
        assertEquals("Discworld", series[0].name)
        assertEquals(1, series[0].bookCount)
        assertEquals("The Colour Of Magic", series[0].items[0].title)
        assertEquals(EbookFormat.Epub, series[0].items[0].ebookFormat)
    }

    @Test
    fun `getSeries parses updatedAt from book items`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"results":[{"id":"ser-1","name":"Discworld","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","updatedAt":1762902014957,"media":{"metadata":{"title":"Colour of Magic","authorName":"Pratchett"},"ebookFormat":"epub"}}]}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val item = (result as NetworkSeriesResult.Success).series[0].items[0]
        assertEquals(1762902014957L, item.updatedAt)
    }

    @Test
    fun `getSeries sets book updatedAt to null when absent`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"results":[{"id":"ser-1","name":"Discworld","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Colour of Magic","authorName":"Pratchett"},"ebookFormat":"epub"}}]}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getSeries(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val item = (result as NetworkSeriesResult.Success).series[0].items[0]
        assertNull(item.updatedAt)
    }

    @Test
    fun `getSeries returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getSeries("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkSeriesResult.NetworkError)
    }

    // ── getCollections ───────────────────────────────────────────────────────

    @Test
    fun `getCollections parses collection list from API response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[{"id":"col-1","name":"My Collection","libraryId":"lib-1","books":[""" +
                    """{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Book One","authorName":"Author A"},"ebookFormat":"epub"}},""" +
                    """{"id":"item-2","libraryId":"lib-1","media":{"metadata":{"title":"Book Two","authorName":"Author B"}}}""" +
                    """]}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getCollections(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        assertTrue(result is NetworkCollectionResult.Success)
        val success = result as NetworkCollectionResult.Success
        assertEquals(1, success.collections.size)
        assertEquals("col-1", success.collections[0].id)
        assertEquals("My Collection", success.collections[0].name)
        assertEquals(2, success.collections[0].bookCount)
        assertEquals(EbookFormat.Epub, success.collections[0].items[0].ebookFormat)
        assertEquals(EbookFormat.Unsupported, success.collections[0].items[1].ebookFormat)
    }

    @Test
    fun `getCollections sends correct path and auth header`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"results":[]}""")
                .addHeader("Content-Type", "application/json")
        )
        client.getCollections(server.url("/").toString().trimEnd('/'), "lib-42", "tok-abc", false)
        val request = server.takeRequest()
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
        assertEquals("/api/libraries/lib-42/collections?limit=500", request.path)
    }

    @Test
    fun `getCollections returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getCollections("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkCollectionResult.NetworkError)
    }
}
