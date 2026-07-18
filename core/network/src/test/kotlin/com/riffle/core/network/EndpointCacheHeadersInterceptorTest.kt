package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class EndpointCacheHeadersInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(EndpointCacheHeadersInterceptor(DEFAULT_HTTP_CACHE_RULES))
            .build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun get(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).build()
    ).execute()

    @Test fun `stamps ABS status with 5-minute TTL`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/status")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals("public, max-age=300", cc)
    }

    @Test fun `stamps ABS libraries list with 15-minute TTL`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/api/libraries")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals("public, max-age=900", cc)
    }

    @Test fun `stamps ABS series with 10-minute TTL for any library id`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/api/libraries/lib-abc-123/series?limit=500")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals("public, max-age=600", cc)
    }

    @Test fun `stamps ABS listening-stats with 60-second TTL`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/api/me/listening-stats")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals("public, max-age=60", cc)
    }

    // Regression: the manual Settings "Check for updates" button is the only caller of the
    // GitHub releases endpoint. Its contract is "check now," so no rule here may stamp Cache-
    // Control on that path. (GitHubReleaseApi.latestRelease also sends CacheControl.FORCE_NETWORK
    // on the request so a re-tap bypasses GitHub's own 60s max-age — verified separately.)
    @Test fun `does not touch GitHub releases endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val r = get("/repos/pkmetski/riffle/releases")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals(null, cc)
    }

    @Test fun `does not touch ABS progress endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/api/me/progress/item-xyz")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals(null, cc)
    }

    @Test fun `does not touch ABS session sync`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = get("/api/session/sess-1/sync")
        val cc = r.header("Cache-Control"); r.close()
        assertEquals(null, cc)
    }

    @Test fun `leaves non-2xx responses untouched even if path matches`() {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Cache-Control", "no-store")
                .setBody("boom")
        )
        val r = get("/api/libraries")
        val cc = r.header("Cache-Control"); r.close()
        assertNotEquals("public, max-age=900", cc)
        assertEquals("no-store", cc)
    }

    @Test fun `does not stamp non-GET even if path matches`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = client.newCall(
            Request.Builder()
                .url(server.url("/api/libraries"))
                .post("{}".toRequestBody())
                .build()
        ).execute()
        val cc = r.header("Cache-Control"); r.close()
        assertEquals(null, cc)
    }
}
