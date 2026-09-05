package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpChannelStreamerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HttpClient(OkHttp)
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.close()
    }

    @Test
    fun `reads body bytes via channel`() = runTest {
        server.enqueue(MockResponse().setBody("hello").addHeader("Content-Type", "text/plain"))
        val result = client.withHttpChannelStream(server.url("/").toString()) { stream ->
            stream.channel.readRemaining().readByteArray().decodeToString()
        }
        assertEquals("hello", result)
    }

    @Test
    fun `retries on 429 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("ok"))
        var callCount = 0
        val result = client.withHttpChannelStream(
            url = server.url("/").toString(),
            retryPolicy = ChannelRetryPolicy(setOf(429), listOf(0L)),
        ) { stream ->
            callCount++
            stream.channel.readRemaining().readByteArray().decodeToString()
        }
        assertEquals("ok", result)
        assertEquals(1, callCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `throws HttpChannelException on terminal non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client.withHttpChannelStream(server.url("/").toString()) { }
            error("expected HttpChannelException")
        } catch (e: HttpChannelException) {
            assertEquals(404, e.failure.code)
        }
    }

    @Test
    fun `exposes content length from response header`() = runTest {
        server.enqueue(MockResponse().setBody("hello world"))
        val result = client.withHttpChannelStream(server.url("/").toString()) { stream ->
            stream.contentLength
        }
        assertEquals(11L, result)
    }
}
