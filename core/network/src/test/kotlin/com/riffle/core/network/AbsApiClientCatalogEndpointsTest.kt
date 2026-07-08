package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.domain.EbookFormat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Endpoint-level coverage for the ABS methods introduced in the catalog abstraction (issue #433).
 * The AbsCatalog-level tests use fakes; these pin JSON parsing, URL construction, HTTP verbs, and
 * auth-header wiring at the network layer.
 */
class AbsApiClientCatalogEndpointsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    // region searchLibrary

    @Test fun `searchLibrary parses book hits from grouped response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""
                {
                  "book": [
                    {"libraryItem": {"id":"b1","libraryId":"lib-a","media":{"metadata":{"title":"Hobbit","authorName":"Tolkien"},"ebookFormat":"epub"}}, "matchKey":"title", "matchText":"Hobbit"},
                    {"libraryItem": {"id":"b2","libraryId":"lib-a","media":{"metadata":{"title":"Silmarillion","authorName":"Tolkien"},"ebookFormat":"epub"}}, "matchKey":"title", "matchText":"Silmarillion"}
                  ],
                  "podcast": [], "authors": [], "tags": [], "series": []
                }
            """.trimIndent())
        )

        val result = client.searchLibrary(baseUrl(), "lib-a", "tolkien", limit = 10, token = "T", insecureAllowed = false)

        assertTrue(result is NetworkResult.Success)
        val items = (result as NetworkResult.Success).value
        assertEquals(2, items.size)
        assertEquals("b1", items[0].id)
        assertEquals("Hobbit", items[0].title)
        assertEquals(EbookFormat.Epub, items[0].ebookFormat)
    }

    @Test fun `searchLibrary URL encodes the query and includes limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"book":[]}"""))

        client.searchLibrary(baseUrl(), "lib-a", "hobbit & rings", limit = 25, token = "T", insecureAllowed = false)

        val recorded = server.takeRequest()
        assertEquals("/api/libraries/lib-a/search?q=hobbit+%26+rings&limit=25", recorded.path)
        assertEquals("Bearer T", recorded.getHeader("Authorization"))
    }

    @Test fun `searchLibrary returns empty list when book group is absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        val result = client.searchLibrary(baseUrl(), "lib-a", "q", 10, "T", false)

        assertTrue(((result as NetworkResult.Success).value).isEmpty())
    }

    // endregion

    // region getItem

    @Test fun `getItem returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getItem(baseUrl(), "gone", "T", false)

        assertTrue(result is NetworkResult.Success)
        assertNull((result as NetworkResult.Success).value)
    }

    @Test fun `getItem parses single item envelope`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""
                {"id":"it-1","libraryId":"lib-a","media":{"metadata":{"title":"A","authorName":"B"},"ebookFormat":"epub","ebookFile":{"ino":"ino-42"},"numAudioFiles":0}}
            """.trimIndent())
        )

        val result = client.getItem(baseUrl(), "it-1", "T", false)

        val item = (result as NetworkResult.Success).value!!
        assertEquals("it-1", item.id)
        assertEquals("A", item.title)
        assertEquals("ino-42", item.ebookFileIno)
    }

    @Test fun `getItem hits expanded item path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        client.getItem(baseUrl(), "it-1", "T", false)

        assertEquals("/api/items/it-1?expanded=1", server.takeRequest().path)
    }

    // endregion

    // region syncPlaybackSession + closePlaybackSession

    @Test fun `syncPlaybackSession posts currentTime and timeListened`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.syncPlaybackSession(baseUrl(), "sess-1", 120.5, 60.0, "T", false)

        assertTrue(result is NetworkResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/sess-1/sync", recorded.path)
        assertEquals("Bearer T", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue("body should include currentTime: $body", body.contains("\"currentTime\":120.5"))
        assertTrue("body should include timeListened: $body", body.contains("\"timeListened\":60"))
    }

    @Test fun `closePlaybackSession hits close endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.closePlaybackSession(baseUrl(), "sess-1", 200.0, 90.0, "T", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/sess-1/close", recorded.path)
    }

    // endregion

    // region getListeningStats

    @Test fun `getListeningStats parses totalTime`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"totalTime":3600.5,"today":600}"""))

        val result = client.getListeningStats(baseUrl(), "T", false)

        val stats = (result as NetworkResult.Success).value
        assertEquals(3600.5, stats.totalTimeSec, 0.0)
    }

    @Test fun `getListeningStats hits me listening-stats endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"totalTime":0}"""))

        client.getListeningStats(baseUrl(), "T", false)

        assertEquals("/api/me/listening-stats", server.takeRequest().path)
    }

    @Test fun `getListeningStats 401 surfaces as Auth`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getListeningStats(baseUrl(), "T", false)

        assertTrue(result is NetworkResult.Auth)
    }

    // endregion
}
