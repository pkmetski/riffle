package com.riffle.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsFileDownloadApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AbsFileDownloadApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AbsFileDownloadApiClient(createDefaultHttpClient(OkHttpClient()))
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

        val result = api.streamFile(
            baseUrl = server.url("/").toString().trimEnd('/'),
            itemId = "book-id",
            fileIno = "file-ino",
            token = "secret-token",
            insecureAllowed = false,
        ) { stream -> stream.contentLength to stream.inputStream.readBytes().decodeToString() }

        assertTrue(result is NetworkResult.Success)
        assertEquals(10L to "epub-bytes", (result as NetworkResult.Success).value)
        val request = server.takeRequest()
        assertEquals("/api/items/book-id/ebook/file-ino", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun `classifies unsuccessful response as server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = api.streamFile(
            baseUrl = server.url("/").toString().trimEnd('/'),
            itemId = "missing",
            fileIno = "file-ino",
            token = "token",
            insecureAllowed = false,
        ) { Unit }

        assertTrue(result is NetworkResult.ServerError)
        assertEquals(404, (result as NetworkResult.ServerError).code)
    }

    @Test
    fun `streamFile exposes bytes before the response finishes`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(64 * 1024, 1, TimeUnit.SECONDS),
        )
        val firstRead = CompletableDeferred<Int>()

        val request = async(Dispatchers.IO) {
            api.streamFile(
                baseUrl = server.url("/").toString().trimEnd('/'),
                itemId = "book-id",
                fileIno = "file-ino",
                token = "token",
                insecureAllowed = false,
            ) { stream ->
                val buffer = ByteArray(64 * 1024)
                val input = stream.inputStream
                firstRead.complete(input.read(buffer))
                while (input.read(buffer) >= 0) {
                    // Drain the live response.
                }
            }
        }

        assertTrue(withTimeout(1_500) { firstRead.await() } > 0)
        assertFalse("response should still be receiving throttled bytes", request.isCompleted)
        assertTrue(request.await() is NetworkResult.Success)
    }
}
