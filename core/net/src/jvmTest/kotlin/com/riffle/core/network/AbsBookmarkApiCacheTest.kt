package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OkHttp-cache-specific test for [AbsApiClient.listBookmarks].
 * Kept in jvmTest because it requires OkHttp [Cache] and [TemporaryFolder] which are
 * platform-specific. The remaining bookmark tests live in commonTest as [AbsBookmarkApiTest].
 */
class AbsBookmarkApiCacheTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    // Regression: EndpointCacheHeadersInterceptor stamps `/api/me` with `Cache-Control: max-age=900`
    // for the details-page perf win, but ABS-bookmark annotation sync (#581) piggybacks on the same
    // endpoint. Without a cache bypass here, device B's live-sync poll gets served the stale body
    // for up to 15 minutes and never sees a peer device's freshly-written annotation until reopen.
    // Mirrors WebDAV, whose PROPFIND was never cached.
    @Test
    fun `listBookmarks bypasses the OkHttp cache so peer writes are visible immediately`() = runTest {
        val cachedHttp = OkHttpClient.Builder()
            .cache(Cache(tempFolder.newFolder(), 1L shl 20))
            .addNetworkInterceptor(EndpointCacheHeadersInterceptor(DEFAULT_HTTP_CACHE_RULES))
            .build()
        val cachedClient = AbsApiClient(createDefaultHttpClient(cachedHttp))

        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"bookmarks":[{"libraryItemId":"ITEM","time":-1,"title":"a","createdAt":1}]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"bookmarks":[{"libraryItemId":"ITEM","time":-1,"title":"a","createdAt":1},""" +
                        """{"libraryItemId":"ITEM","time":-2,"title":"peer","createdAt":2}]}"""
                )
        )

        val first = cachedClient.listBookmarks(baseUrl(), "tok", false)
        val second = cachedClient.listBookmarks(baseUrl(), "tok", false)

        assertTrue(first is NetworkResult.Success)
        assertTrue(second is NetworkResult.Success)
        // If the cache were consulted, the second call would replay the first body and never see
        // the peer bookmark — proving both calls went to the origin.
        assertEquals(1, (first as NetworkResult.Success).value.size)
        assertEquals(2, (second as NetworkResult.Success).value.size)
        assertEquals(2, server.requestCount)
    }
}
