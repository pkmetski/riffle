package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class ForceCacheHeadersInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(ForceCacheHeadersInterceptor(maxAgeSeconds = 3600))
            .build()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `rewrites successful response to forced max-age`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                .setHeader("Pragma", "no-cache")
                .setBody("<html/>")
        )

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        val cc = response.header("Cache-Control")
        val pragma = response.header("Pragma")
        response.close()

        assertEquals("public, max-age=3600", cc)
        assertEquals(null, pragma)
    }

    @Test fun `leaves non-2xx responses untouched`() {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Cache-Control", "private")
                .setBody("nope")
        )

        val response = client.newCall(Request.Builder().url(server.url("/y")).build()).execute()
        val cc = response.header("Cache-Control")
        response.close()

        assertNotEquals("public, max-age=3600", cc)
        assertEquals("private", cc)
    }
}
