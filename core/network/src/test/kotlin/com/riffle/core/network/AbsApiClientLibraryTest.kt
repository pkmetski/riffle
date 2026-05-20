package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        client = AbsApiClient(OkHttpClient())
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
        assertTrue(result is NetworkLibrariesResult.Success)
        val success = result as NetworkLibrariesResult.Success
        assertEquals(2, success.libraries.size)
        assertEquals("lib-1", success.libraries[0].id)
        assertEquals("My Books", success.libraries[0].name)
        assertEquals("book", success.libraries[0].mediaType)
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
        assertTrue(result is NetworkLibrariesResult.NetworkError)
    }

    @Test
    fun `getLibraryItems success parses items with ebook progress`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"My Book","authorName":"Author A"}},"userMediaProgress":{"progress":0.5,"ebookProgress":0.42}}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token-abc", false
        )
        assertTrue(result is NetworkLibraryItemsResult.Success)
        val success = result as NetworkLibraryItemsResult.Success
        assertEquals(1, success.items.size)
        assertEquals("item-1", success.items[0].id)
        assertEquals("My Book", success.items[0].title)
        assertEquals("Author A", success.items[0].author)
        assertEquals(0.42f, success.items[0].readingProgress, 0.001f)
    }

    @Test
    fun `getLibraryItems uses 0 progress when userMediaProgress is null`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Unread Book","authorName":"Author B"}}}]}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token-abc", false
        )
        val success = result as NetworkLibraryItemsResult.Success
        assertEquals(0f, success.items[0].readingProgress, 0.001f)
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
        assertEquals("/api/libraries/lib-99/items?minified=1", request.path)
    }

    @Test
    fun `getLibraryItems returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.getLibraryItems("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkLibraryItemsResult.NetworkError)
    }
}
