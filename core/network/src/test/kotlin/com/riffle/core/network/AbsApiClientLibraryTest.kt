package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.riffle.core.models.EbookFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientLibraryTest {

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

    @Test
    fun `getLibraries success parses all libraries`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"libraries":[{"id":"lib-1","name":"My Books","mediaType":"book"},{"id":"lib-2","name":"Podcasts","mediaType":"podcast"}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraries(server.url("/").toString().trimEnd('/'), "token-abc", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(2, success.value.size)
        assertEquals("lib-1", success.value[0].id)
        assertEquals("My Books", success.value[0].name)
        assertEquals("book", success.value[0].mediaType)
    }

    @Test
    fun `getLibraries parses audiobooksOnly from settings`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"libraries":[{"id":"lib-1","name":"Books","mediaType":"book","settings":{"audiobooksOnly":false}},{"id":"lib-2","name":"Audiobooks","mediaType":"book","settings":{"audiobooksOnly":true}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraries(server.url("/").toString().trimEnd('/'), "token", false)
        val success = result as NetworkResult.Success
        assertEquals(false, success.value[0].audiobooksOnly)
        assertEquals(true, success.value[1].audiobooksOnly)
    }

    @Test
    fun `getLibraries sends Authorization Bearer header and calls correct path`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"libraries":[]}""")
                .addHeader("Content-Type", "application/json")
        )
        client.getLibraries(server.url("/").toString().trimEnd('/'), "my-token", false)
        val request = server.takeRequest()
        assertEquals("Bearer my-token", request.getHeader("Authorization"))
        assertEquals("/api/libraries", request.path)
    }

    @Test
    fun `getLibraries returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getLibraries("http://127.0.0.1:1", "token", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems marks ebook items as supported and audiobook items as unsupported`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[""" +
                    """{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"My Ebook","authorName":"Author A"},"ebookFormat":"epub"},"userMediaProgress":{"progress":0.5,"ebookProgress":0.42}},""" +
                    """{"id":"item-2","libraryId":"lib-1","media":{"metadata":{"title":"My Audiobook","authorName":"Author B"}}}""" +
                    """]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token-abc", false
        )
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(2, success.value.size)
        assertEquals(EbookFormat.Epub, success.value[0].ebookFormat)
        assertEquals(0.42f, success.value[0].readingProgress!!, 0.001f)
        assertEquals(EbookFormat.Unsupported, success.value[1].ebookFormat)
    }

    @Test
    fun `getLibraryItems sets hasAudio from numAudioFiles and numTracks, false when absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[""" +
                    """{"id":"ebook","libraryId":"lib-1","media":{"metadata":{"title":"Ebook","authorName":"A"},"ebookFormat":"epub"}},""" +
                    """{"id":"audio","libraryId":"lib-1","media":{"metadata":{"title":"Audiobook","authorName":"B"},"numAudioFiles":6,"duration":39214.5}},""" +
                    """{"id":"audio-tracks","libraryId":"lib-1","media":{"metadata":{"title":"Audiobook2","authorName":"C"},"numTracks":3}},""" +
                    """{"id":"combined","libraryId":"lib-1","media":{"metadata":{"title":"Both","authorName":"D"},"ebookFormat":"epub","numAudioFiles":2}}""" +
                    """]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token-abc", false
        )
        val items = (result as NetworkResult.Success).value.associateBy { it.id }
        assertFalse(items.getValue("ebook").hasAudio)
        assertTrue(items.getValue("audio").hasAudio)
        assertTrue(items.getValue("audio-tracks").hasAudio)
        assertTrue(items.getValue("combined").hasAudio)
        assertEquals(39214.5, items.getValue("audio").audioDurationSec, 0.001)
        assertEquals(0.0, items.getValue("ebook").audioDurationSec, 0.001)
    }

    @Test
    fun `getLibraryItems uses 0 progress when userMediaProgress is null`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Unread Book","authorName":"Author B"},"ebookFormat":"pdf"}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token-abc", false
        )
        val success = result as NetworkResult.Success
        assertNull(success.value[0].readingProgress)
    }

    @Test
    fun `getLibraryItems sends correct path and auth header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[]}""")
                .addHeader("Content-Type", "application/json")
        )
        client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-99", "tok-xyz", false
        )
        val request = server.takeRequest()
        assertEquals("Bearer tok-xyz", request.getHeader("Authorization"))
        assertEquals("/api/libraries/lib-99/items", request.path)
    }

    @Test
    fun `getLibraryItems returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getLibraryItems("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems returns NetworkError on 404 with plain-text body`() = runTest {
        // ABS returns plain text "Library not found" with HTTP 404 when a stale/removed library
        // id is queried. Parsing that body as JSON used to crash the app on the library screen.
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Library not found")
                .addHeader("Content-Type", "text/plain")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-gone", "tok", false
        )
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems parses addedAt timestamp`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","addedAt":1708369906982,"media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertEquals(1708369906982L, success.value[0].addedAt)
    }

    @Test
    fun `getLibraryItems parses updatedAt timestamp`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","updatedAt":1719000000000,"media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertEquals(1719000000000L, success.value[0].updatedAt)
    }

    @Test
    fun `getLibraryItems sets updatedAt to null when field is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertNull(success.value[0].updatedAt)
    }

    @Test
    fun `getLibraryItems sets addedAt to null when field is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(server.url("/").toString().trimEnd('/'), "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertNull(success.value[0].addedAt)
    }

    // --- /api/me mediaProgress.lastUpdate (drives cross-device "In Progress" sort) ---

    @Test
    fun `getUserProgress parses lastUpdate from real-shaped mediaProgress entries`() = runTest {
        // Body modelled on a real ABS /api/me response — extra fields ignored, lastUpdate captured.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"mediaProgress":[
                        {"id":"p1","userId":"u","libraryItemId":"item-1","mediaItemType":"book",
                         "progress":0.0,"ebookProgress":0.015625,"isFinished":false,
                         "lastUpdate":1780170049396,"startedAt":1779780482497},
                        {"id":"p2","userId":"u","libraryItemId":"item-2","mediaItemType":"book",
                         "progress":0.0,"ebookProgress":0.5,"isFinished":false,
                         "lastUpdate":1779642817411}
                    ]}""".trimIndent()
                )
        )

        val result = client.getUserProgress(server.url("/").toString().trimEnd('/'), "tok", false)

        assertTrue(result is NetworkResult.Success)
        val byItemId = (result as NetworkResult.Success).value
        assertEquals(2, byItemId.size)
        assertEquals(1_780_170_049_396L, byItemId["item-1"]?.lastUpdate)
        assertEquals(0.015625f, byItemId["item-1"]?.ebookProgress)
        assertEquals(1_779_642_817_411L, byItemId["item-2"]?.lastUpdate)
    }

    @Test
    fun `getUserProgress surfaces audiobook progress even when ebookProgress is zero`() = runTest {
        // An audiobook entry: real listen `progress`, with `ebookProgress` 0 (no ebook). The mapping
        // must not let the 0 ebookProgress shadow the listen position (ADR 0029) — regression for
        // "audiobook progress not visible in the library".
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"mediaProgress":[
                        {"libraryItemId":"audio-1","mediaItemType":"book",
                         "progress":0.42,"ebookProgress":0.0,"isFinished":false,"lastUpdate":1780170049396}
                    ]}""".trimIndent()
                )
        )

        val result = client.getUserProgress(server.url("/").toString().trimEnd('/'), "tok", false)

        val byItemId = (result as NetworkResult.Success).value
        assertEquals(0.42f, byItemId["audio-1"]?.ebookProgress)
    }

    @Test
    fun `getUserProgress yields null lastUpdate when field is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"mediaProgress":[{"libraryItemId":"item-1","ebookProgress":0.25}]}""")
        )

        val result = client.getUserProgress(server.url("/").toString().trimEnd('/'), "tok", false)

        val byItemId = (result as NetworkResult.Success).value
        assertNull(byItemId["item-1"]?.lastUpdate)
        assertEquals(0.25f, byItemId["item-1"]?.ebookProgress)
    }
}
