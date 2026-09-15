package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O'Reilly audiobooks are Kaltura-hosted: getTracks/openAudiobook assemble one session from
 * videotocs → videoclips (kaltura_entry_id) → kaltura_config/session → a Kaltura HLS URL per chapter.
 * Verified live 2026-09; this pins the assembly against captured fixtures.
 */
class OReillyAudiobookTest {

    private val BASE_URL = "http://test"
    private val itemId = "9781663721174"

    private var breakSecondClip = false
    private lateinit var client: HttpClient
    private lateinit var catalog: OReillyCatalog

    @BeforeTest
    fun setUp() {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path.startsWith("/api/v1/videotocs/") -> respond(
                    """{"identifier":"$itemId","toc":[
                      {"reference_id":"$itemId-a00001","linkid":"a00001","title":"Introduction","duration":400},
                      {"reference_id":"$itemId-a00002","linkid":"a00002","title":"Chapter 2","duration":600}
                    ]}""".trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
                path.startsWith("/api/v1/player/kaltura_config/") -> respond(
                    """{"partner_id":"1926081"}""", HttpStatusCode.OK, jsonHeaders,
                )
                path.startsWith("/api/v1/player/kaltura_session/") -> respond(
                    """{"session":"KS123","expiry":"2099-01-01T00:00:00"}""", HttpStatusCode.OK, jsonHeaders,
                )
                path.startsWith("/api/v1/videoclips/$itemId-a00001/") -> respond(
                    """{"reference_id":"$itemId-a00001","kaltura_entry_id":"1_aaa"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                path.startsWith("/api/v1/videoclips/$itemId-a00002/") -> {
                    val body = if (breakSecondClip)
                        """{"reference_id":"$itemId-a00002","kaltura_entry_id":""}"""
                    else
                        """{"reference_id":"$itemId-a00002","kaltura_entry_id":"1_bbb"}"""
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        client = HttpClient(engine)
        val api = OReillyApi(client, cookieHeader = "orm-jwt=x", baseUrl = BASE_URL)
        catalog = OReillyCatalog(api = api, bytesClient = client, cookieHeader = "orm-jwt=x")
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `getTracks builds ordered Kaltura HLS tracks with cumulative offsets`() = runTest {
        val tracks = catalog.getTracks(itemId)

        assertEquals(2, tracks.size)
        assertEquals(tracks[0].ino, "$itemId-a00001")
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
    fun `openAudiobook returns tracks chapters with real titles and total duration`() = runTest {
        val stream = catalog.openAudiobook(itemId, deviceLabel = "test")!!

        assertEquals(2, stream.trackUrls.size)
        assertEquals(1000.0, stream.totalDurationSec, 0.001)
        assertEquals(listOf("Introduction", "Chapter 2"), stream.chapters.map { it.title })
        assertEquals(0.0, stream.chapters[0].startSec, 0.001)
        assertEquals(400.0, stream.chapters[0].endSec, 0.001)
        assertEquals(1000.0, stream.chapters[1].endSec, 0.001)
    }

    @Test
    fun `an unresolved chapter fails the whole session so tracks never diverge from the toc`() = runTest {
        breakSecondClip = true
        assertTrue(catalog.getTracks(itemId).isEmpty())
        assertNull(catalog.openAudiobook(itemId, deviceLabel = "test"))
    }

    @Test
    fun `openAudiobook populates downloadTrackUrls with format-url MP4 paths`() = runTest {
        val stream = catalog.openAudiobook(itemId, deviceLabel = "test")!!

        val dlUrls = stream.downloadTrackUrls
        assertEquals(2, dlUrls?.size)
        assertEquals(
            "https://cdnapisec.kaltura.com/p/1926081/sp/192608100/playManifest/entryId/1_aaa" +
                "/format/url/protocol/https?ks=KS123",
            dlUrls?.get(0),
        )
        assertTrue(dlUrls?.get(1)?.contains("entryId/1_bbb") == true)
        assertTrue(dlUrls?.get(1)?.contains("format/url") == true)
        // HLS stream URLs are still present for the player.
        assertTrue(stream.trackUrls[0].contains("format/applehttp"))
    }

    @Test
    fun `getAudiobookChapters comes from the toc without a Kaltura session`() = runTest {
        val chapters = catalog.getAudiobookChapters(itemId)
        assertEquals(listOf("Introduction", "Chapter 2"), chapters.map { it.title })
        assertEquals(400.0, chapters[1].startSec, 0.001)
    }
}
