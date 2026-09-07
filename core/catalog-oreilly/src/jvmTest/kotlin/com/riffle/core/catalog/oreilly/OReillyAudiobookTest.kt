package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O'Reilly audiobooks are Kaltura-hosted: getTracks/openAudiobook assemble one session from
 * videotocs → videoclips (kaltura_entry_id) → kaltura_config/session → a Kaltura HLS URL per chapter.
 * Verified live 2026-09; this pins the assembly against captured fixtures.
 */
class OReillyAudiobookTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient
    private lateinit var catalog: OReillyCatalog

    private val itemId = "9781663721174"

    // When true, chapter 2's videoclip resolves to a blank Kaltura entry id (simulating a failed
    // resolve) so the all-or-nothing assembly can be exercised.
    private var breakSecondClip = false

    // Routes by path so the concurrent per-chapter videoclips fetches resolve correctly.
    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = when {
                path.startsWith("/api/v1/videotocs/") -> """
                    {"identifier":"$itemId","toc":[
                      {"reference_id":"$itemId-a00001","linkid":"a00001","title":"Introduction","duration":400},
                      {"reference_id":"$itemId-a00002","linkid":"a00002","title":"Chapter 2","duration":600}
                    ]}
                """.trimIndent()
                path.startsWith("/api/v1/player/kaltura_config/") -> """{"partner_id":"1926081"}"""
                path.startsWith("/api/v1/player/kaltura_session/") -> """{"session":"KS123","expiry":"2099-01-01T00:00:00"}"""
                path.startsWith("/api/v1/videoclips/$itemId-a00001/") ->
                    """{"reference_id":"$itemId-a00001","kaltura_entry_id":"1_aaa"}"""
                path.startsWith("/api/v1/videoclips/$itemId-a00002/") ->
                    if (breakSecondClip) """{"reference_id":"$itemId-a00002","kaltura_entry_id":""}"""
                    else """{"reference_id":"$itemId-a00002","kaltura_entry_id":"1_bbb"}"""
                else -> return MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
            }
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { dispatcher = this@OReillyAudiobookTest.dispatcher; start() }
        client = HttpClient(OkHttp)
        val api = OReillyApi(client, cookieHeader = "orm-jwt=x", baseUrl = server.url("/").toString())
        catalog = OReillyCatalog(api = api, bytesClient = client, cookieHeader = "orm-jwt=x")
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `getTracks builds ordered Kaltura HLS tracks with cumulative offsets`() = runBlocking {
        val tracks = catalog.getTracks(itemId)

        assertEquals(2, tracks.size)
        assertEquals("$itemId-a00001", tracks[0].ino)
        assertEquals(0, tracks[0].index)
        assertEquals(0.0, tracks[0].startOffsetSec, 0.001)
        assertEquals(400.0, tracks[0].durationSec, 0.001)
        assertEquals(OReillyApi.HLS_MIME, tracks[0].mimeType)
        assertEquals(
            "https://cdnapisec.kaltura.com/p/1926081/sp/192608100/playManifest/entryId/1_aaa" +
                "/format/applehttp/protocol/https/a.m3u8?ks=KS123",
            tracks[0].contentUrl,
        )
        // Second chapter starts where the first ends.
        assertEquals(400.0, tracks[1].startOffsetSec, 0.001)
        assertTrue(tracks[1].contentUrl.contains("entryId/1_bbb"))
    }

    @Test
    fun `openAudiobook returns tracks, chapters with real titles, and total duration`() = runBlocking {
        val stream = catalog.openAudiobook(itemId, deviceLabel = "test")!!

        assertEquals(2, stream.trackUrls.size)
        assertEquals(1000.0, stream.totalDurationSec, 0.001)
        assertEquals(listOf("Introduction", "Chapter 2"), stream.chapters.map { it.title })
        assertEquals(0.0, stream.chapters[0].startSec, 0.001)
        assertEquals(400.0, stream.chapters[0].endSec, 0.001)
        assertEquals(1000.0, stream.chapters[1].endSec, 0.001)
    }

    @Test
    fun `an unresolved chapter fails the whole session so tracks never diverge from the toc`() = runBlocking {
        breakSecondClip = true
        assertTrue(catalog.getTracks(itemId).isEmpty())
        assertNull(catalog.openAudiobook(itemId, deviceLabel = "test"))
    }

    @Test
    fun `getAudiobookChapters comes from the toc without a Kaltura session`() = runBlocking {
        val chapters = catalog.getAudiobookChapters(itemId)
        assertEquals(listOf("Introduction", "Chapter 2"), chapters.map { it.title })
        assertEquals(400.0, chapters[1].startSec, 0.001)
    }
}
