package com.riffle.core.catalog.gutenberg

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GutenbergHttpClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun newClient() = GutenbergHttpClient(
        client = OkHttpClient(),
        userAgent = "Riffle/test",
        retryDelaysMs = listOf(0L, 0L),
    )

    @Test
    fun `succeeds on first 200`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        val body = newClient().getString(server.url("/x").toString())
        assertEquals("hello", body)
    }

    @Test
    fun `retries once on 429 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("world"))
        val body = newClient().getString(server.url("/x").toString())
        assertEquals("world", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries once on 503 then succeeds`() = runTest {
        // gutenberg.org's EPUB mirror occasionally 503s during mirror rotation — verify the
        // retry schedule covers that in addition to the standard 429.
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("world"))
        val body = newClient().getString(server.url("/x").toString())
        assertEquals("world", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `throws after three 429s`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        try {
            newClient().getString(server.url("/x").toString())
            fail("expected GutenbergHttpException")
        } catch (e: GutenbergHttpException) {
            assertEquals(429, e.code)
        }
    }

    @Test
    fun `non-retryable error is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            newClient().getString(server.url("/x").toString())
            fail("expected exception")
        } catch (e: GutenbergHttpException) {
            assertEquals(500, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `user-agent is sent`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        newClient().getString(server.url("/x").toString())
        val ua = server.takeRequest().getHeader("User-Agent")
        assertTrue("expected Riffle UA, got $ua", ua?.startsWith("Riffle/") == true)
    }

    @Test
    fun `ping returns true on 200 and false on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))
        val http = newClient()
        assertTrue(http.ping(server.url("/x").toString()))
        assertTrue(!http.ping(server.url("/y").toString()))
    }
}
