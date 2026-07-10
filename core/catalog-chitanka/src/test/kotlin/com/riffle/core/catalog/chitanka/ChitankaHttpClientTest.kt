package com.riffle.core.catalog.chitanka

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

class ChitankaHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient() = ChitankaHttpClient(
        client = OkHttpClient(),
        userAgent = "Riffle/test",
        retryDelaysMs = listOf(0L, 0L),  // no wait in tests
    )

    @Test
    fun `succeeds on first 200`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        val http = newClient()
        val body = http.getString(server.url("/x").toString())
        assertEquals("hello", body)
    }

    @Test
    fun `retries once on 429 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("world"))
        val http = newClient()
        val body = http.getString(server.url("/x").toString())
        assertEquals("world", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `throws after three 429s`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val http = newClient()
        try {
            http.getString(server.url("/x").toString())
            fail("expected ChitankaHttpException")
        } catch (e: ChitankaHttpException) {
            assertEquals(429, e.code)
        }
    }

    @Test
    fun `non-429 error is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val http = newClient()
        try {
            http.getString(server.url("/x").toString())
            fail("expected exception")
        } catch (e: ChitankaHttpException) {
            assertEquals(500, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `user-agent is sent`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val http = newClient()
        http.getString(server.url("/x").toString())
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
