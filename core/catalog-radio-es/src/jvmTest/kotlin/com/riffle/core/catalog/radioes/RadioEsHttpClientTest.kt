package com.riffle.core.catalog.radioes

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioEsHttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RadioEsHttpClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = RadioEsHttpClient(
            client = HttpClient(OkHttp),
            userAgent = "Riffle/test",
            retryDelaysMs = emptyList(),
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `getString returns body on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))
        val result = client.getString(server.url("/podcasts/search").toString())
        assertEquals("""{"ok":true}""", result)
    }

    @Test
    fun `getString retries once on 429 then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val clientWithOneRetry = RadioEsHttpClient(
            client = HttpClient(OkHttp),
            userAgent = "Riffle/test",
            retryDelaysMs = listOf(0L),
        )
        val result = clientWithOneRetry.getString(server.url("/").toString())
        assertEquals("{}", result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `getString throws RadioEsHttpException on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client.getString(server.url("/").toString())
            org.junit.Assert.fail("Expected RadioEsHttpException")
        } catch (e: RadioEsHttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `ping returns true on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.ping(server.url("/").toString()))
    }

    @Test
    fun `ping returns false on network failure`() = runBlocking {
        server.shutdown()
        val result = client.ping("http://localhost:1")
        assertFalse(result)
    }

    @Test
    fun `getString sends User-Agent header`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        client.getString(server.url("/").toString())
        val request = server.takeRequest()
        assertEquals("Riffle/test", request.getHeader("User-Agent"))
    }

    @Test
    fun `getString sends Accept header for JSON`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        client.getString(server.url("/").toString())
        val request = server.takeRequest()
        val accept = request.getHeader("Accept") ?: ""
        assertTrue("Accept should mention application/json, got: $accept", accept.contains("application/json"))
    }
}
