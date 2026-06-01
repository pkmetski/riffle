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
import kotlinx.coroutines.test.runTest

class StorytellerBundleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var impl: StorytellerBundleApiImpl
    private lateinit var api: StorytellerBundleApi
    private lateinit var probe: StorytellerBundleProbeApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        impl = StorytellerBundleApiImpl(OkHttpClient())
        api = impl
        probe = impl
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test fun downloadBundle_callsExpectedPath_withBearerAuth() = runTest {
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

    @Test fun downloadBundle_nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkStorytellerBundleResult.NetworkError)
    }

    @Test fun probeBundleSize_returnsContentLength_fromHeadResponse() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", "315074677"))

        val result = probe.probeBundleSize(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue("Expected Success but got $result", result is NetworkStorytellerBundleSizeResult.Success)
        assertEquals(315_074_677L, (result as NetworkStorytellerBundleSizeResult.Success).sizeBytes)
    }

    @Test fun probeBundleSize_nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = probe.probeBundleSize(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkStorytellerBundleSizeResult.NetworkError)
    }
}
