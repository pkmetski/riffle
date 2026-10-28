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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O'Reilly audiobooks are Kaltura-hosted: getTracks/openAudiobook assemble one session from
 * videotocs → videoclips (kaltura_entry_id) → kaltura_config/session → a Kaltura HLS URL per chapter.
 * Verified live 2026-09; this pins the assembly against captured fixtures.
 *
 * Also covers getItem with AU-suffixed IDs (e.g. "9781663721174AU"): the EPUB metadata endpoint
 * uses the bare ISBN, and the returned item must be forced to BookFormat.Audiobook so the ABS
 * upload pre-flight and import path treat it correctly.
 */
class OReillyAudiobookTest {

    private val BASE_URL = "http://test"
    private val itemId = "9781663721174"
    private val audioItemId = "${itemId}AU"

    private var breakSecondClip = false
    private lateinit var client: HttpClient
    private lateinit var catalog: OReillyCatalog

    @BeforeTest
    fun setUp() {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                // Only the bare ISBN (without AU) is a valid videotocs key.
                path == "/api/v1/videotocs/$itemId/" -> respond(
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
                // EPUB metadata endpoint — used by getItem for both bare and AU-suffix IDs
                path.startsWith("/api/v2/epubs/urn:orm:book:$itemId/") -> respond(
                    """{"identifier":"$itemId","title":"The Manager's Path","language":"en","content_format":"book"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
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
        // track.ino must be the Kaltura download URL (not the referenceId) so withTrackStream can
        // byte-stream it for ABS import — the referenceId is not a URL.
        assertEquals(
            "https://cdnapisec.kaltura.com/p/1926081/sp/192608100/playManifest/entryId/1_aaa" +
                "/format/url/protocol/https?ks=KS123",
            tracks[0].ino,
        )
        assertEquals(0, tracks[0].index)
        assertEquals(0.0, tracks[0].startOffsetSec, 0.001)
        assertEquals(400.0, tracks[0].durationSec, 0.001)
        // mimeType reflects the actual bytes served by the download URL (MP4), not the HLS manifest.
        assertEquals("audio/mp4", tracks[0].mimeType)
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

    @Test
    fun `getItem with AU-suffix queries the EPUB endpoint with the bare ISBN`() = runTest {
        // The upload pre-flight calls getItem(audioItemId). Before this fix, bookDetailUrl was
        // called with the AU-suffixed ID, causing a 404 and returning null — "cannot be prepared
        // for upload". After the fix, the AU suffix is stripped before the EPUB request.
        val item = catalog.getItem(audioItemId)

        assertNotNull(item)
        assertEquals(audioItemId, item.id)
    }

    @Test
    fun `getItem with AU-suffix forces format to Audiobook regardless of EPUB endpoint response`() = runTest {
        val item = catalog.getItem(audioItemId)!!

        assertEquals(com.riffle.core.catalog.BookFormat.Audiobook, item.ebookFormat)
        assertEquals(true, item.hasAudio)
        assertEquals(OReillyRoots.AUDIOBOOKS, item.rootId)
    }

    @Test
    fun `getItem without AU-suffix returns an ebook item unchanged`() = runTest {
        val item = catalog.getItem(itemId)

        assertNotNull(item)
        assertEquals(itemId, item.id)
        assertEquals(com.riffle.core.catalog.BookFormat.Epub, item.ebookFormat)
        assertEquals(false, item.hasAudio)
    }

    @Test
    fun `getTracks with AU-suffix strips the suffix before calling videotocs`() = runTest {
        // The videotocs mock only responds to the bare ISBN path; it returns 404 for the AU-
        // suffixed path. If loadAudiobookTracks did NOT strip the suffix, getTracks would return
        // an empty list here — which would cause the ABS upload to fail with "no files".
        val tracks = catalog.getTracks(audioItemId)

        assertEquals(2, tracks.size)
        assertEquals(0, tracks[0].index)
        assertEquals(400.0, tracks[0].durationSec, 0.001)
        assertTrue(tracks[0].ino.contains("format/url"))
    }

    @Test
    fun `getAudiobookChapters with AU-suffix strips the suffix before calling videotocs`() = runTest {
        val chapters = catalog.getAudiobookChapters(audioItemId)

        assertEquals(listOf("Introduction", "Chapter 2"), chapters.map { it.title })
    }
}
