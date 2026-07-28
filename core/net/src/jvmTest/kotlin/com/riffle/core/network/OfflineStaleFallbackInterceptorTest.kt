package com.riffle.core.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class OfflineStaleFallbackInterceptorTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun clientWithCache(): OkHttpClient {
        val cache = Cache(tempFolder.newFolder("okcache"), 1_000_000L)
        return OkHttpClient.Builder()
            .cache(cache)
            .addNetworkInterceptor(ForceCacheHeadersInterceptor(maxAgeSeconds = 3600))
            .addInterceptor(OfflineStaleFallbackInterceptor())
            .build()
    }

    @Test fun `serves cached copy when network fails`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("fresh-body"))

        val client = clientWithCache()
        val url = server.url("/z")

        // Warm the cache.
        client.newCall(Request.Builder().url(url).build()).execute().close()

        // Take the server down so the next request throws.
        server.shutdown()

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body.string()
        response.close()

        assertEquals("fresh-body", body)
        // A network-served response would have `networkResponse != null`; a cache-served one
        // has `cacheResponse != null` and `networkResponse == null`.
        assertTrue("stale fallback should have served from cache", response.networkResponse == null)
    }

    @Test fun `rethrows original IOException when cache is empty`() {
        val client = clientWithCache()
        val url = server.url("/miss")
        server.shutdown()

        try {
            client.newCall(Request.Builder().url(url).build()).execute()
            fail("expected IOException — nothing cached and network is down")
        } catch (io: IOException) {
            // Expected — no cached copy, no network.
        }
    }
}
