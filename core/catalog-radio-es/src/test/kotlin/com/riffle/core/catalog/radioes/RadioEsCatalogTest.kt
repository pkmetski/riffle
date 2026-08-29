package com.riffle.core.catalog.radioes

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioEsCatalogTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: RadioEsCatalog

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = RadioEsHttpClient(
            client = HttpClient(OkHttp),
            userAgent = "Riffle/test",
            retryDelaysMs = emptyList(),
        )
        catalog = RadioEsCatalog(
            http = http,
            apiBase = server.url("").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() { server.shutdown() }

    // ---- Contract ------------------------------------------------------------

    @Test fun `sourceType is RADIO_ES`() = runTest {
        assertEquals(SourceType.RADIO_ES, catalog.sourceType)
    }

    @Test fun `listRoots returns a single Podcasts root`() = runTest {
        val roots = catalog.listRoots()
        assertEquals(1, roots.size)
        assertEquals(RadioEsCatalog.ROOT_PODCASTS, roots.single().id)
        assertEquals("Podcasts", roots.single().name)
        assertEquals("audiobook", roots.single().mediaType)
    }

    @Test fun `catalog implements AudiobookMediaCapability`() {
        assertTrue(catalog is AudiobookMediaCapability)
    }

    @Test fun `catalog implements ToReadListCapability`() {
        assertTrue(catalog is ToReadListCapability)
    }

    @Test fun `catalog implements OfflineBrowseCapability`() {
        assertTrue(catalog is OfflineBrowseCapability)
    }

    // ---- Facets -------------------------------------------------------------

    @Test fun `listFacets returns categories from tags endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-tags.json")))
        val facets = catalog.listFacets(RadioEsCatalog.ROOT_PODCASTS)
        assertTrue("expected at least one category facet, got $facets", facets.isNotEmpty())
        assertTrue(facets.any { it.key == "slug:news" })
    }

    @Test fun `listFacets caches result after first fetch`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-tags.json")))
        catalog.listFacets(RadioEsCatalog.ROOT_PODCASTS)
        catalog.listFacets(RadioEsCatalog.ROOT_PODCASTS)
        assertEquals("server should only be hit once for facets", 1, server.requestCount)
    }

    @Test fun `listFacets returns empty for unknown root`() = runTest {
        assertTrue(catalog.listFacets("bogus").isEmpty())
    }

    // ---- Browse -------------------------------------------------------------

    @Test fun `browse hits category charts endpoint with correct offset`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-podcasts-page1.json")))
        catalog.browse(rootId = RadioEsCatalog.ROOT_PODCASTS, page = 0, pageSize = 20)
        val request = server.takeRequest()
        assertTrue(
            "path should start with /podcasts/category/podcasts/charts, got: ${request.path}",
            request.path?.startsWith("/podcasts/category/podcasts/charts") == true,
        )
        assertTrue("offset=0 expected, got: ${request.path}", request.path?.contains("offset=0") == true)
    }

    @Test fun `browse page 1 sends offset equal to pageSize`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-podcasts-page1.json")))
        catalog.browse(rootId = RadioEsCatalog.ROOT_PODCASTS, page = 1, pageSize = 20)
        val request = server.takeRequest()
        assertTrue("offset=20 expected, got: ${request.path}", request.path?.contains("offset=20") == true)
    }

    @Test fun `browse with category facet uses category slug in charts path`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-podcasts-page1.json")))
        catalog.browse(
            rootId = RadioEsCatalog.ROOT_PODCASTS,
            page = 0,
            pageSize = 20,
            facet = FacetSelection(key = "slug:news"),
        )
        val request = server.takeRequest()
        assertTrue(
            "/podcasts/category/news/charts expected, got: ${request.path}",
            request.path?.startsWith("/podcasts/category/news/charts") == true,
        )
    }

    @Test fun `browse maps podcasts to CatalogItems`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-podcasts-page1.json")))
        val items = catalog.browse(rootId = RadioEsCatalog.ROOT_PODCASTS, page = 0, pageSize = 20)
        assertEquals(2, items.size)
        val daily = items.first { it.id == "the-daily" }
        assertEquals("The Daily", daily.title)
        assertEquals("The New York Times", daily.author)
        assertEquals(RadioEsCatalog.ROOT_PODCASTS, daily.rootId)
        assertTrue("hasAudio should be true for podcasts", daily.hasAudio)
    }

    // ---- Search -------------------------------------------------------------

    @Test fun `search sends query param`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-podcasts-page1.json")))
        catalog.search(rootId = RadioEsCatalog.ROOT_PODCASTS, query = "crime", page = 0, pageSize = 20)
        val request = server.takeRequest()
        assertTrue("query=crime expected, got: ${request.path}", request.path?.contains("query=crime") == true)
    }

    @Test fun `search returns empty for blank query`() = runTest {
        val items = catalog.search(rootId = RadioEsCatalog.ROOT_PODCASTS, query = "  ", page = 0, pageSize = 20)
        assertTrue(items.isEmpty())
        assertEquals("server should not be contacted for blank query", 0, server.requestCount)
    }

    // ---- getItem ------------------------------------------------------------

    @Test fun `getItem fetches from podcasts detail endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-detail.json")))
        val item = catalog.getItem("the-daily")
        assertNotNull(item)
        assertEquals("the-daily", item!!.id)
        assertEquals("The Daily", item.title)
        assertEquals("This is what the news should sound like.", item.description)
    }

    @Test fun `getItem returns null on empty detail response`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val item = catalog.getItem("unknown-podcast")
        assertNull(item)
    }

    // ---- AudiobookMediaCapability ------------------------------------------

    @Test fun `getTracks fetches episodes and maps to CatalogAudioTracks`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-episodes.json")))
        val cap = catalog as AudiobookMediaCapability
        val tracks = cap.getTracks("the-daily")
        assertEquals(2, tracks.size)
        val t1 = tracks[0]
        assertEquals(0, t1.index)
        assertEquals(0.0, t1.startOffsetSec, 0.001)
        assertEquals(1800.0, t1.durationSec, 0.001)
        assertEquals("https://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/test/ep1.mp3", t1.ino)
        val t2 = tracks[1]
        assertEquals(1, t2.index)
        assertEquals(1800.0, t2.startOffsetSec, 0.001)
        assertEquals(2400.0, t2.durationSec, 0.001)
    }

    @Test fun `buildStreamUrl returns the trackIno directly`() = runTest {
        val cap = catalog as AudiobookMediaCapability
        val url = cap.buildStreamUrl("the-daily", "https://host/ep.mp3")
        assertEquals("https://host/ep.mp3", url)
    }

    @Test fun `openAudiobook returns stream with correct totalDurationSec`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-episodes.json")))
        val cap = catalog as AudiobookMediaCapability
        val stream = cap.openAudiobook("the-daily", "TestDevice")
        assertNotNull(stream)
        assertEquals(4200.0, stream!!.totalDurationSec, 0.001)
        assertEquals(2, stream.tracks.size)
        assertEquals(2, stream.chapters.size)
    }

    @Test fun `getAudiobookChapters returns one chapter per episode with episode title`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("radioes-episodes.json")))
        val cap = catalog as AudiobookMediaCapability
        val chapters = cap.getAudiobookChapters("the-daily")
        assertEquals(2, chapters.size)
        assertEquals("Episode One", chapters[0].title)
        assertEquals("Episode Two", chapters[1].title)
        assertEquals(0.0, chapters[0].startSec, 0.001)
        assertEquals(1800.0, chapters[0].endSec, 0.001)
        assertEquals(1800.0, chapters[1].startSec, 0.001)
    }

    @Test fun `getFingerprint returns null`() = runTest {
        val cap = catalog as AudiobookMediaCapability
        val fp = cap.getFingerprint("the-daily")
        assertNull(fp)
    }

    // ---- Connectivity -------------------------------------------------------

    @Test fun `connectivityCheck returns health with isReachable true on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val health = catalog.connectivityCheck()
        assertNotNull(health)
        assertTrue(health.isReachable)
    }
}
