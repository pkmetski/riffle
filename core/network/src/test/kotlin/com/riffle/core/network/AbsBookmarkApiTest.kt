package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsBookmarkApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `createBookmark posts time and title and parses returned bookmark`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"libraryItemId":"ITEM","time":123,"title":"x","createdAt":111}""")
        )
        val result = client.createBookmark(baseUrl(), "ITEM", 123, "x", "tok", false)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/me/item/ITEM/bookmark", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"time\":123"))
        assertTrue(body.contains("\"title\":\"x\""))

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals("ITEM", bookmark.libraryItemId)
        assertEquals(123, bookmark.timeSec)
        assertEquals("x", bookmark.title)
        assertEquals(111L, bookmark.createdAt)
    }

    @Test
    fun `updateBookmark patches to same path and parses returned bookmark`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"libraryItemId":"ITEM","time":123,"title":"x","createdAt":111}""")
        )
        val result = client.updateBookmark(baseUrl(), "ITEM", 123, "x", "tok", false)

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/item/ITEM/bookmark", req.path)

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals(123, bookmark.timeSec)
        assertEquals("x", bookmark.title)
    }

    @Test
    fun `deleteBookmark deletes by time and synthesizes success from plain-text OK`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .setBody("OK")
        )
        val result = client.deleteBookmark(baseUrl(), "ITEM", 123, "tok", false)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/me/item/ITEM/bookmark/123", req.path)

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals("ITEM", bookmark.libraryItemId)
        assertEquals(123, bookmark.timeSec)
    }

    @Test
    fun `deleteBookmark404IsTreatedAsSuccess`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
        val result = client.deleteBookmark(baseUrl(), "ITEM", 123, "tok", false)

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/me/item/ITEM/bookmark/123", req.path)

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals("ITEM", bookmark.libraryItemId)
        assertEquals(123, bookmark.timeSec)
    }

    @Test
    fun `listBookmarks parses bookmarks array from api me`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"bookmarks":[{"libraryItemId":"ITEM","time":5,"title":"a","createdAt":1},""" +
                        """{"libraryItemId":"ITEM","time":9,"title":"b","createdAt":2}],"id":"u","username":"x"}"""
                )
        )
        val result = client.listBookmarks(baseUrl(), "tok", false)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/me", req.path)

        assertTrue(result is NetworkResult.Success)
        val bookmarks = (result as NetworkResult.Success).value
        assertEquals(2, bookmarks.size)
        assertEquals(5, bookmarks[0].timeSec)
        assertEquals("a", bookmarks[0].title)
        assertEquals(1L, bookmarks[0].createdAt)
        assertEquals(9, bookmarks[1].timeSec)
        assertEquals("b", bookmarks[1].title)
    }

    @Test
    fun `listBookmarks returns empty list when bookmarks key absent`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"u","username":"x"}""")
        )
        val result = client.listBookmarks(baseUrl(), "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).value.isEmpty())
    }

    @Test
    fun `createBookmark returns NetworkError on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        val result = client.createBookmark(baseUrl(), "ITEM", 123, "x", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }
}
