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

class AbsEpubDownloadApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AbsEpubDownloadApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AbsEpubDownloadApiClient(createDefaultHttpClient(OkHttpClient()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams epub bytes with content length and bearer token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("epub-bytes")
                .setHeader("Content-Length", 10),
        )

        val result = api.downloadEpub(
            baseUrl = server.url("/").toString().trimEnd('/'),
            itemId = "book-id",
            fileIno = "file-ino",
            token = "secret-token",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkResult.Success)
        val stream = (result as NetworkResult.Success).value
        assertEquals(10L, stream.contentLength)
        stream.inputStream.use {
            assertEquals("epub-bytes", it.readBytes().decodeToString())
        }
        val request = server.takeRequest()
        assertEquals("/api/items/book-id/ebook/file-ino", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun `classifies unsuccessful response as server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = api.downloadEpub(
            baseUrl = server.url("/").toString().trimEnd('/'),
            itemId = "missing",
            fileIno = "file-ino",
            token = "token",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkResult.ServerError)
        assertEquals(404, (result as NetworkResult.ServerError).code)
    }
}
