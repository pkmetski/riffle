package com.riffle.core.network

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
import java.util.concurrent.TimeUnit

class HttpByteStreamerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: io.ktor.client.HttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = createDefaultHttpClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `exposes bytes before response completion`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(64 * 1024, 1, TimeUnit.SECONDS),
        )
        val firstRead = CompletableDeferred<Int>()

        val request = async(Dispatchers.IO) {
            client.withHttpByteStream(server.url("/file").toString()) { stream ->
                val buffer = ByteArray(64 * 1024)
                firstRead.complete(stream.inputStream.read(buffer))
                while (stream.inputStream.read(buffer) >= 0) {
                    // Drain the live response.
                }
            }
        }

        assertTrue(withTimeout(1_500) { firstRead.await() } > 0)
        assertFalse("response should still be receiving throttled bytes", request.isCompleted)
        request.await()
    }

    @Test
    fun `retries configured statuses inside the same streaming module`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("bytes"))

        val body = client.withHttpByteStream(
            url = server.url("/file").toString(),
            retryPolicy = HttpRetryPolicy(statusCodes = setOf(503), delaysMs = listOf(0L)),
        ) { stream -> stream.inputStream.readBytes().decodeToString() }

        assertEquals("bytes", body)
        assertEquals(2, server.requestCount)
    }
}
