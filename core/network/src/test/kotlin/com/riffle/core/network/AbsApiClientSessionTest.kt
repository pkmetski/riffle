package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientSessionTest {

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
    fun `syncEbookProgress sends PATCH to correct path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/item-1", req.path)
    }

    @Test
    fun `syncEbookProgress sends ebookLocation and ebookProgress in body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(body.contains("\"ebookProgress\":0.25"))
    }

    // A plain reader position save (isFinished = null) must NOT carry isFinished, or every save
    // would risk flipping the item's finished/audio state on the server.
    @Test
    fun `syncEbookProgress omits isFinished from body when null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.25f, isFinished = null),
            "tok", false,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("isFinished"))
    }

    // A mark-read/unread carries isFinished so ABS also resets the audio dimension in the same PATCH.
    @Test
    fun `syncEbookProgress includes isFinished in body when set`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("", 0.0f, isFinished = false),
            "tok", false,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"isFinished\":false"))
    }

    @Test
    fun `syncEbookProgress sends Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "my-token", false,
        )
        val req = server.takeRequest()
        assertEquals("Bearer my-token", req.getHeader("Authorization"))
    }

    @Test
    fun `syncEbookProgress returns Success on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.syncEbookProgress(
            "http://127.0.0.1:1", "item-1",
            NetworkEbookProgressPayload("cfi", 0f),
            "tok", false,
        )
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate 0 when server responds with plain-text OK`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .setBody("OK")
        )
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(0L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate when server responds with JSON containing lastUpdate`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ebookLocation":"cfi","lastUpdate":1779445105751}""")
        )
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(1779445105751L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate 0 when server responds with empty JSON`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{}")
        )
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(0L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `getProgress parses complex CFI with element ID and character offset`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ebookLocation":"epubcfi(/6/160!/4/4[heading_id_2]/1:0)","lastUpdate":1779445105751}""")
        )
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals("epubcfi(/6/160!/4/4[heading_id_2]/1:0)", progress.ebookLocation)
        assertEquals(1779445105751L, progress.lastUpdate)
    }

    @Test
    fun `getProgress parses all standard ABS fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id":"prog-1",
                        "userId":"user-1",
                        "libraryItemId":"item-1",
                        "episodeId":null,
                        "mediaItemId":"item-1",
                        "mediaItemType":"book",
                        "duration":3600.0,
                        "progress":0.9897289586305278,
                        "currentTime":3560.0,
                        "isFinished":false,
                        "hideFromContinueListening":false,
                        "ebookLocation":"epubcfi(/6/160!/4/4[heading_id_2]/1:0)",
                        "ebookProgress":0.9897289586305278,
                        "lastUpdate":1779445105751,
                        "startedAt":1779000000000,
                        "finishedAt":null
                    }
                """.trimIndent())
        )
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals("epubcfi(/6/160!/4/4[heading_id_2]/1:0)", progress.ebookLocation)
        assertEquals(1779445105751L, progress.lastUpdate)
    }

    @Test
    fun `getProgress 404 returns Success with lastUpdate zero and empty ebookLocation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals("", progress.ebookLocation)
        assertEquals(0f, progress.ebookProgress, 0.001f)
        assertEquals(0L, progress.lastUpdate)
    }

    @Test
    fun `getProgress non-404 non-2xx returns NetworkError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getProgress parses ebookProgress field from ABS response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ebookLocation":"epubcfi(/6/4!/4/2/1:5)","ebookProgress":0.42,"lastUpdate":9000}""")
        )
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals(0.42f, progress.ebookProgress, 0.001f)
        assertEquals(9000L, progress.lastUpdate)
    }

    @Test
    fun `getProgress returns NetworkError on empty body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = client.getProgress(baseUrl(), "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }
}
