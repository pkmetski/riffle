package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertEquals(0.75f, items[0].readingProgress, 0.001f)
        assertTrue(items[0].isSupported)
        assertNull(items[1].sequence)
        assertEquals(0f, items[1].readingProgress, 0.001f)
        assertFalse(items[1].isSupported)
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
        assertEquals("/api/libraries/lib-99/series?minified=1", request.path)
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
        assertTrue(success.collections[0].items[0].isSupported)
        assertFalse(success.collections[0].items[1].isSupported)
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
        assertEquals("/api/libraries/lib-42/collections", request.path)
    }

    @Test
    fun `getCollections returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getCollections("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkCollectionResult.NetworkError)
    }
}
