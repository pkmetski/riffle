package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class StorytellerBundleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StorytellerBundleApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StorytellerBundleApiImpl(OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun downloadBundle_callsExpectedPath_withBearerAuth() = runBlocking {
        val bytes = ByteArray(64) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkStorytellerBundleResult.Success)
        val readBytes = (result as NetworkStorytellerBundleResult.Success).body.use { it.bytes() }
        assertEquals(bytes.toList(), readBytes.toList())
    }

    @Test fun downloadBundle_nonSuccess_returnsNetworkError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkStorytellerBundleResult.NetworkError)
    }
}
