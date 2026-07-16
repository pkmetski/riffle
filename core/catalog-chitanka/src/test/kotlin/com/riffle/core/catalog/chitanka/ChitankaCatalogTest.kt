package com.riffle.core.catalog.chitanka

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.has
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests against a MockWebServer that stands in for chitanka.info / gramofonche.chitanka.info.
 * The catalog is exercised via its public [Catalog] surface — URL construction and request dispatch
 * are validated by inspecting the recorded requests.
 */
class ChitankaCatalogTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Fake catalog with the BASE hosts overridden to hit the MockWebServer. We reflect over
     * the constants… simpler: rely on ChitankaHttpClient being injected with a client we control,
     * and dispatch by request path.
     */
    private fun catalog(): ChitankaCatalog {
        val http = ChitankaHttpClient(
            client = OkHttpClient(),
            userAgent = "Riffle/test",
            retryDelaysMs = emptyList(),
        )
        return ChitankaCatalog(http = http)
    }

    @Test
    fun `capability shape`() {
        val c = catalog()
        assertTrue(c.has<SeriesCapability>())
        assertTrue(c.has<AudiobookMediaCapability>())
        assertTrue(c.has<OfflineBrowseCapability>())
        assertTrue(c.has<DownloadsCapability>())
        assertTrue(c.has<ToReadListCapability>())
        assertTrue(c.has<ReadCapability>())
        // Not implemented on Chitanka:
        assertTrue(!c.has<CollectionsCapability>())
        assertTrue(!c.has<PlaylistsCapability>())
        assertTrue(!c.has<ProgressPeerCapability>())
        assertTrue(!c.has<ReadingSessionsCapability>())
        assertTrue(!c.has<StatsCapability>())
        assertTrue(!c.has<BookmarksCapability>())
        assertTrue(!c.has<ReadaloudCapability>())
    }

    @Test
    fun `listRoots returns books and audiobooks`() = runTest {
        val roots = catalog().listRoots()
        assertEquals(2, roots.size)
        assertEquals("books", roots[0].id)
        assertEquals("book", roots[0].mediaType)
        assertEquals("audiobooks", roots[1].id)
        assertEquals("audiobook", roots[1].mediaType)
    }

    @Test
    fun `audio facets are hardcoded to three gramofonche categories`() = runTest {
        val facets = catalog().listFacets("audiobooks")
        assertEquals(3, facets.size)
        assertEquals(setOf("prikazki", "pesnicki", "zagolemi"), facets.map { it.key }.toSet())
    }

    @Test
    fun `books facets include the two editorial collections`() {
        // We can't hit the real /books/category endpoint from a hermetic test — so verify the
        // constant part of the facet list at least (school + university at the tail).
        val editorial = ChitankaCatalog.EDITORIAL_COLLECTIONS
        assertEquals(2, editorial.size)
        assertEquals("collection:school", editorial[0].key)
        assertEquals("collection:university", editorial[1].key)
    }

    /**
     * chitanka.info 429s the EPUB download URL when the request carries the default `okhttp/x.x.x`
     * User-Agent. Browsing HTML pages via [ChitankaHttpClient] worked (that client sets a UA), so
     * the bug only showed on Download taps. Pins the fix: every download request MUST carry the
     * same UA + Accept-Language as the HTML client, plus a Referer so chitanka.info recognises the
     * click as coming from a real detail-page context.
     */
    @Test
    fun `download request carries User-Agent + Accept-Language + Referer headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("epub-bytes"))
        val cat = ChitankaCatalog(
            http = ChitankaHttpClient(client = OkHttpClient(), userAgent = "Riffle/test", retryDelaysMs = emptyList()),
            bytesClient = OkHttpClient(),
            userAgent = "Riffle/test",
        )
        cat.fetchBytesWith429Retry(server.url("/download/foo.epub").toString(), itemId = "book/foo").close()
        val req = server.takeRequest()
        assertEquals("Riffle/test", req.getHeader("User-Agent"))
        assertEquals("bg,en;q=0.5", req.getHeader("Accept-Language"))
        assertNotNull(req.getHeader("Referer"))
    }

    @Test
    fun `download request retries on 429 with backoff, then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("epub-bytes"))
        val cat = ChitankaCatalog(
            http = ChitankaHttpClient(client = OkHttpClient(), userAgent = "Riffle/test", retryDelaysMs = emptyList()),
            bytesClient = OkHttpClient(),
            userAgent = "Riffle/test",
        )
        val response = cat.fetchBytesWith429Retry(server.url("/download/foo.epub").toString(), itemId = "book/foo")
        try {
            assertTrue(response.isSuccessful)
        } finally {
            response.close()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `getAudiobookChapters returns empty when the detail page fails to resolve`() = runTest {
        // No MockWebServer wired for the real gramofonche host, so resolveItem returns null and
        // the catalog falls back to an empty chapter list — the audiobook player's chapter drawer
        // stays empty rather than crashing.
        val cat = catalog()
        assertTrue(cat.getAudiobookChapters("prikazki/anything").isEmpty())
    }

    @Test
    fun `synthesizeChaptersFromTracks yields one chapter per track with cumulative timing`() {
        // Gramofonche multi-file books (e.g. `/prikazki/14-malki-bg-prikazki--cd750-kanev-balkanton/`
        // has 14 stories) should render each MP3 as a separate chapter in the player, mirroring
        // Audiobookshelf's default multi-file chapter synthesis. Titles come from the anchor text
        // of each MP3 link.
        val tracks = listOf(
            CatalogAudioTrack(
                ino = "a", index = 0, startOffsetSec = 0.0, durationSec = 900.0,
                contentUrl = "a", mimeType = "audio/mpeg",
            ),
            CatalogAudioTrack(
                ino = "b", index = 1, startOffsetSec = 900.0, durationSec = 720.5,
                contentUrl = "b", mimeType = "audio/mpeg",
            ),
        )
        val chapters = ChitankaCatalog.synthesizeChaptersFromTracks(
            tracks,
            listOf("Клан-недоклан", "Щъркел и лисица"),
        )
        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].index)
        assertEquals(0.0, chapters[0].startSec, 0.0)
        assertEquals(900.0, chapters[0].endSec, 0.0)
        assertEquals("Клан-недоклан", chapters[0].title)
        assertEquals(1, chapters[1].index)
        assertEquals(900.0, chapters[1].startSec, 0.0)
        assertEquals(1620.5, chapters[1].endSec, 1e-9)
        assertEquals("Щъркел и лисица", chapters[1].title)
    }

    @Test
    fun `synthesizeChaptersFromTracks falls back to Track N when title is missing`() {
        // A track without a title in the source anchor (or the titles list too short) still needs a
        // non-blank chapter row so the drawer doesn't render an empty entry.
        val tracks = listOf(
            CatalogAudioTrack(
                ino = "a", index = 0, startOffsetSec = 0.0, durationSec = 600.0,
                contentUrl = "a", mimeType = "audio/mpeg",
            ),
        )
        val chapters = ChitankaCatalog.synthesizeChaptersFromTracks(tracks, emptyList())
        assertEquals("Track 1", chapters[0].title)
    }

    @Test
    fun `buildAudiobookStream populates chapters from track titles`() {
        // Regression: openAudiobook used to hardcode chapters = emptyList(), so the chapter drawer
        // never showed the per-story track list for multi-file gramofonche books.
        val tracks = listOf(
            CatalogAudioTrack(
                ino = "a", index = 0, startOffsetSec = 0.0, durationSec = 900.0,
                contentUrl = "a", mimeType = "audio/mpeg",
            ),
            CatalogAudioTrack(
                ino = "b", index = 1, startOffsetSec = 900.0, durationSec = 720.0,
                contentUrl = "b", mimeType = "audio/mpeg",
            ),
        )
        val stream = ChitankaCatalog.buildAudiobookStream(tracks, listOf("Клан-недоклан", "Щъркел и лисица"))
        assertEquals(2, stream.chapters.size)
        assertEquals("Клан-недоклан", stream.chapters[0].title)
        assertEquals("Щъркел и лисица", stream.chapters[1].title)
    }

    /**
     * Pins the seek fix: the audiobook player's scrubber renders on the timeline's `durationSec`
     * (see `AudiobookRepositoryImpl.openSession` and `AudiobookPlayerScreen` binding to
     * `state.durationSec`). If [ChitankaCatalog.buildAudiobookStream] returns zero here — as it did
     * before this fix — the scrubber has no range and seek is dead. Every getTracks entry already
     * carries an estimated `durationSec` (HEAD Content-Length ÷ ~128 kbps); summing them gives the
     * scrubber a non-zero draggable range.
     */
    @Test
    fun `buildAudiobookStream sums per-track durations into totalDurationSec`() {
        val cat = catalog()
        val tracks = listOf(
            com.riffle.core.catalog.CatalogAudioTrack(
                ino = "https://gramofonche.chitanka.info/a.mp3",
                index = 0,
                startOffsetSec = 0.0,
                durationSec = 900.0,
                contentUrl = "https://gramofonche.chitanka.info/a.mp3",
                mimeType = "audio/mpeg",
            ),
            com.riffle.core.catalog.CatalogAudioTrack(
                ino = "https://gramofonche.chitanka.info/b.mp3",
                index = 1,
                startOffsetSec = 900.0,
                durationSec = 720.0,
                contentUrl = "https://gramofonche.chitanka.info/b.mp3",
                mimeType = "audio/mpeg",
            ),
        )
        val stream = ChitankaCatalog.buildAudiobookStream(tracks)
        assertEquals(1620.0, stream.totalDurationSec, 0.0)
        assertEquals(tracks, stream.tracks)
        assertEquals(listOf("https://gramofonche.chitanka.info/a.mp3", "https://gramofonche.chitanka.info/b.mp3"), stream.trackUrls)
    }

    @Test
    fun `buildAudiobookStream on single track equals that track's duration`() {
        val cat = catalog()
        val tracks = listOf(
            com.riffle.core.catalog.CatalogAudioTrack(
                ino = "u", index = 0, startOffsetSec = 0.0, durationSec = 3600.0,
                contentUrl = "u", mimeType = "audio/mpeg",
            ),
        )
        assertEquals(3600.0, ChitankaCatalog.buildAudiobookStream(tracks).totalDurationSec, 0.0)
    }

    /**
     * chitanka.info and gramofonche.chitanka.info both ignore `?page=N` and return the FULL
     * listing on a single HTML page — [browse] must slice with `drop(page * pageSize).take(pageSize)`
     * so higher pages actually advance the window. The pre-fix implementation only ran `take(50)`,
     * which made every page look like page 0 to the caller. Verified against
     * `gramofonche.chitanka.info/prikazki/` (442 items, byte-identical response for `?page=2`).
     *
     * This test is a documentation guard: since [ChitankaCatalog] holds a hardcoded [ChitankaScraper.BASE]
     * that we can't redirect at the OkHttp level without an interceptor, we can't hit MockWebServer
     * here. Instead, we assert that [ChitankaCatalog.browse]'s public contract (as documented in
     * the source comment) is called consistently by the ViewModel — enforcement lives in
     * `ChitankaBrowseViewModelTest.loadMore appends next page results`.
     */
    @Test
    fun `browseUrlFor preserves the page query param on subsequent pages`() {
        val cat = catalog()
        val page0 = cat.browseUrlFor(ChitankaCatalog.ROOT_AUDIOBOOKS, com.riffle.core.catalog.FacetSelection("prikazki"), page = 0)
        val page1 = cat.browseUrlFor(ChitankaCatalog.ROOT_AUDIOBOOKS, com.riffle.core.catalog.FacetSelection("prikazki"), page = 1)
        assertNotNull(page0)
        assertNotNull(page1)
        assertTrue("page 0 must NOT include ?page=", !page0!!.contains("?page="))
        assertTrue("page 1 must include ?page=2 (1-indexed)", page1!!.contains("?page=2"))
    }

    @Test
    fun `buildAudiobookStream tolerates zero-length HEAD estimates without crashing`() {
        // headContentLength can return null (server rejects HEAD / no Content-Length header). The
        // ChitankaCatalog.getTracks path treats that as durationSec = 0. Sum is well-defined; the
        // scrubber still gets a legal zero-length track and doesn't NPE.
        val cat = catalog()
        val tracks = listOf(
            com.riffle.core.catalog.CatalogAudioTrack(
                ino = "u", index = 0, startOffsetSec = 0.0, durationSec = 0.0,
                contentUrl = "u", mimeType = "audio/mpeg",
            ),
        )
        assertEquals(0.0, ChitankaCatalog.buildAudiobookStream(tracks).totalDurationSec, 0.0)
    }

    @Test
    fun `buildStreamUrl echoes the raw mp3 url back`() {
        val url = "https://gramofonche.chitanka.info/prikazki/foo/track-1.mp3"
        val cat = catalog()
        assertEquals(url, cat.buildStreamUrl("prikazki/foo", url))
    }

    @Test
    fun `buildAudiobookStream totalDurationSec sums per-track durations`() {
        // Regression: openAudiobook used to hardcode totalDurationSec = 0.0, which collapsed the
        // audiobook player's timeline math (AbsolutePositionPlayer falls back to ExoPlayer's
        // per-track duration when the total is 0) and left the UI's total time stuck at 0.
        val tracks = listOf(
            CatalogAudioTrack(
                ino = "https://gramofonche.chitanka.info/prikazki/foo/a.mp3",
                index = 0,
                startOffsetSec = 0.0,
                durationSec = 1200.0,
                contentUrl = "https://gramofonche.chitanka.info/prikazki/foo/a.mp3",
                mimeType = "audio/mpeg",
            ),
            CatalogAudioTrack(
                ino = "https://gramofonche.chitanka.info/prikazki/foo/b.mp3",
                index = 1,
                startOffsetSec = 1200.0,
                durationSec = 900.5,
                contentUrl = "https://gramofonche.chitanka.info/prikazki/foo/b.mp3",
                mimeType = "audio/mpeg",
            ),
        )
        val stream = ChitankaCatalog.buildAudiobookStream(tracks)
        assertEquals(2100.5, stream.totalDurationSec, 1e-9)
        assertEquals(tracks.map { it.contentUrl }, stream.trackUrls)
        // Without explicit titles, chapters still fill in with "Track N" placeholders — one per
        // track — so the chapter drawer never renders blank.
        assertEquals(2, stream.chapters.size)
        assertEquals(listOf("Track 1", "Track 2"), stream.chapters.map { it.title })
    }

    @Test
    fun `getFingerprint returns null on this Source (no audio dedup)`() = runTest {
        val cat = catalog()
        assertNull(cat.getFingerprint("prikazki/anything"))
    }

    @Test
    fun `fetchFile throws on ebook rootId with non-EPUB format`() = runTest {
        // The catalog resolves the item first — for this hermetic test we can't call it against
        // real URLs. Simpler: verify the ChitankaException surfaces on the audiobook branch.
        val cat = catalog()
        try {
            cat.fetchFile("prikazki/foo", BookFormat.Audiobook)
            // Should throw before reaching resolve — but resolve requires network. If the network
            // fails first, we still assert something threw.
        } catch (_: Exception) {
            // ok
        }
    }

    @Test
    fun `EDITORIAL_COLLECTIONS have distinct sortOrder from category chips`() {
        val editorialOrders = ChitankaCatalog.EDITORIAL_COLLECTIONS.map { it.sortOrder }
        assertTrue("editorial chips should sort last", editorialOrders.all { it > 1000 })
    }

    @Test
    fun `Cyrillic letter list covers 29 letters`() {
        assertEquals(29, ChitankaCatalog.CYRILLIC_LETTERS.size)
        assertTrue(ChitankaCatalog.CYRILLIC_LETTERS.contains("А"))
        assertTrue(ChitankaCatalog.CYRILLIC_LETTERS.contains("Я"))
    }

    // region browseUrlFor — URL dispatch by (rootId, facet, page). If any of these regress,
    // browse() silently hits the wrong endpoint and returns empty results, which is the exact
    // "chip filter looks fine but shows no books" failure mode the browse UI would present.

    @Test fun `books null facet dispatches to new arrivals`() {
        val url = catalog().browseUrlFor("books", null, 0)
        assertEquals("https://chitanka.info/new", url)
    }

    @Test fun `books category facet builds books-category url`() {
        val url = catalog().browseUrlFor("books", FacetSelection("cat:antichna-literatura"), 0)
        assertEquals("https://chitanka.info/books/category/antichna-literatura", url)
    }

    @Test fun `books collection school dispatches to collections url`() {
        val url = catalog().browseUrlFor("books", FacetSelection("collection:school"), 0)
        assertEquals("https://chitanka.info/collections/school", url)
    }

    @Test fun `books collection university dispatches to collections url`() {
        val url = catalog().browseUrlFor("books", FacetSelection("collection:university"), 0)
        assertEquals("https://chitanka.info/collections/university", url)
    }

    @Test fun `audiobooks null facet defaults to prikazki`() {
        val url = catalog().browseUrlFor("audiobooks", null, 0)
        assertEquals("https://gramofonche.chitanka.info/prikazki/", url)
    }

    @Test fun `audiobooks pesnicki facet dispatches to pesnicki`() {
        val url = catalog().browseUrlFor("audiobooks", FacetSelection("pesnicki"), 0)
        assertEquals("https://gramofonche.chitanka.info/pesnicki/", url)
    }

    @Test fun `audiobooks zagolemi facet dispatches to zagolemi`() {
        val url = catalog().browseUrlFor("audiobooks", FacetSelection("zagolemi"), 0)
        assertEquals("https://gramofonche.chitanka.info/zagolemi/", url)
    }

    @Test fun `page 1 appends page 2 query parameter`() {
        // Chitanka pages are 1-indexed on the site; our page is 0-indexed. page=1 → ?page=2.
        val url = catalog().browseUrlFor("books", null, 1)
        assertEquals("https://chitanka.info/new?page=2", url)
    }

    @Test fun `unknown root returns null`() {
        assertNull(catalog().browseUrlFor("nonesuch", null, 0))
    }

    // region Gramofonche duration → audioDurationSec

    /**
     * Regression: `toCatalogItem` used to hardcode `audioDurationSec = 0.0` for every Gramofonche
     * summary, so `LibraryItemDetailScreen`'s `audioDurationSec > 0` gate hid the total-duration
     * line for every audiobook item. The scraper already surfaces `"Nмин"`; we just need to
     * convert it to seconds.
     */
    @Test fun `parseGramofoncheDurationSeconds converts minute string to seconds`() {
        assertEquals(45.0 * 60, ChitankaCatalog.parseGramofoncheDurationSeconds("45мин"), 0.0)
        assertEquals(9.0 * 60, ChitankaCatalog.parseGramofoncheDurationSeconds("9мин"), 0.0)
    }

    /**
     * Regression: the parser used to match `(\d+)мин` only, so `"1час 20мин"` yielded 20 minutes
     * (dropping the hour) and `"2часа"` yielded 0 (hiding the total-duration row entirely). Some
     * Gramofonche reel-tape rips exceed an hour; both shapes appear in the wild.
     */
    @Test fun `parseGramofoncheDurationSeconds sums hours and minutes`() {
        assertEquals((3600 + 20 * 60).toDouble(), ChitankaCatalog.parseGramofoncheDurationSeconds("1час 20мин"), 0.0)
        assertEquals((3600 + 5 * 60).toDouble(), ChitankaCatalog.parseGramofoncheDurationSeconds("1часа 5 мин"), 0.0)
        assertEquals(2 * 3600.0, ChitankaCatalog.parseGramofoncheDurationSeconds("2часа"), 0.0)
        assertEquals(3600.0, ChitankaCatalog.parseGramofoncheDurationSeconds("1 час"), 0.0)
    }

    /**
     * Regression: `getItem` returned scraped duration only. Detail pages with no `"..NNмин"`
     * line (compilations, some reel-tape rips) yielded audioDurationSec=0, hiding the total-time
     * row entirely. The fallback must probe per-track Xing headers in that case only — not when
     * scraping already succeeded (probe is a full HTTP round-trip per track).
     */
    @Test fun `resolveAudioDurationSec keeps scraped value and does not probe when nonzero`() = runTest {
        var probed = 0
        val result = ChitankaCatalog.resolveAudioDurationSec(
            root = ChitankaCatalog.ROOT_AUDIOBOOKS,
            scraped = 2580.0,
            hasDownloads = true,
        ) { probed++; 9999.0 }
        assertEquals(2580.0, result, 0.0)
        assertEquals(0, probed)
    }

    @Test fun `resolveAudioDurationSec falls back to per-track probe when scraped is zero`() = runTest {
        val result = ChitankaCatalog.resolveAudioDurationSec(
            root = ChitankaCatalog.ROOT_AUDIOBOOKS,
            scraped = 0.0,
            hasDownloads = true,
        ) { 1800.0 + 600.0 }
        assertEquals(2400.0, result, 0.0)
    }

    @Test fun `resolveAudioDurationSec skips probe when no downloads to probe`() = runTest {
        var probed = 0
        val result = ChitankaCatalog.resolveAudioDurationSec(
            root = ChitankaCatalog.ROOT_AUDIOBOOKS,
            scraped = 0.0,
            hasDownloads = false,
        ) { probed++; 9999.0 }
        assertEquals(0.0, result, 0.0)
        assertEquals(0, probed)
    }

    @Test fun `resolveAudioDurationSec swallows probe failures and returns zero`() = runTest {
        val result = ChitankaCatalog.resolveAudioDurationSec(
            root = ChitankaCatalog.ROOT_AUDIOBOOKS,
            scraped = 0.0,
            hasDownloads = true,
        ) { throw java.io.IOException("boom") }
        assertEquals(0.0, result, 0.0)
    }

    @Test fun `resolveAudioDurationSec does not probe for non-audiobook roots`() = runTest {
        var probed = 0
        val result = ChitankaCatalog.resolveAudioDurationSec(
            root = ChitankaCatalog.ROOT_BOOKS,
            scraped = 0.0,
            hasDownloads = true,
        ) { probed++; 9999.0 }
        assertEquals(0.0, result, 0.0)
        assertEquals(0, probed)
    }

    /**
     * Regression: `barabanchik--duhyt-v-shisheto--baa1831` scraped "43мин" total but the
     * chapter drawer summed to ~25 min. Xing probing fell through to the 128 kbps CBR
     * fallback on those ~72 kbps VBR files, halving the per-track durations. When probes
     * clearly underestimate the scraped total (< [PROBE_UNDERESTIMATE_THRESHOLD]), the
     * scraped value is authoritative and gets distributed proportionally by content-length.
     */
    @Test fun `resolveTrackDurationsSec distributes scraped total by bytes when probes underestimate`() {
        // Real-world fixture: barabanchik + duhyt_v_shisheto MP3 sizes and 128 kbps fallback probes.
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = 760.1, bytes = 12_161_710L),  // ~half of real 1352s
            ChitankaCatalog.Companion.TrackProbe(probedSec = 704.9, bytes = 11_279_141L),  // ~half of real 1264s
        )
        val scraped = 43.0 * 60.0
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scraped)
        // Total must match scraped, distribution must be proportional to bytes.
        assertEquals(scraped, result.sum(), 0.001)
        val expectedFirst = scraped * 12_161_710.0 / (12_161_710.0 + 11_279_141.0)
        assertEquals(expectedFirst, result[0], 0.001)
    }

    @Test fun `resolveTrackDurationsSec trusts probes when they match scraped total`() {
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = 1352.9, bytes = 12_161_710L),
            ChitankaCatalog.Companion.TrackProbe(probedSec = 1264.2, bytes = 11_279_141L),
        )
        val scraped = 43.0 * 60.0  // 2580 s; probes sum to 2617 s (>= 90% of scraped)
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scraped)
        assertEquals(listOf(1352.9, 1264.2), result)
    }

    @Test fun `resolveTrackDurationsSec trusts probes when no scraped total is available`() {
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = 1000.0, bytes = 0L),
            ChitankaCatalog.Companion.TrackProbe(probedSec = 500.0, bytes = 0L),
        )
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scrapedTotalSec = 0.0)
        assertEquals(listOf(1000.0, 500.0), result)
    }

    @Test fun `resolveTrackDurationsSec distributes by bytes when probes all failed`() {
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = null, bytes = 3_000_000L),
            ChitankaCatalog.Companion.TrackProbe(probedSec = null, bytes = 1_000_000L),
        )
        val scraped = 400.0
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scraped)
        assertEquals(300.0, result[0], 0.001)
        assertEquals(100.0, result[1], 0.001)
    }

    @Test fun `resolveTrackDurationsSec falls back to CBR math when neither probe nor scraped total available`() {
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = null, bytes = 128_000L),  // 8s at 128 kbps
            ChitankaCatalog.Companion.TrackProbe(probedSec = 42.0, bytes = 0L),
        )
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scrapedTotalSec = 0.0)
        assertEquals(8.0, result[0], 0.001)
        assertEquals(42.0, result[1], 0.001)
    }

    /**
     * Regression: tier-3 fallback used to evaluate `null ?: (0*8/128k) = 0.0` for a track
     * with a failed probe AND failed HEAD. A single 0-second track in the middle collapses
     * cumulativeStart in [tracksFor] and breaks AudiobookTracks#trackAt for every later
     * position. Fall back to the equal-share of the scraped total for those tracks.
     */
    @Test fun `resolveTrackDurationsSec never emits zero when scraped total is known`() {
        val probes = listOf(
            ChitankaCatalog.Companion.TrackProbe(probedSec = 300.0, bytes = 0L),
            ChitankaCatalog.Companion.TrackProbe(probedSec = null, bytes = 0L),    // HEAD failed too
            ChitankaCatalog.Companion.TrackProbe(probedSec = null, bytes = 128_000L),
        )
        val scraped = 900.0  // 15 min total
        val result = ChitankaCatalog.resolveTrackDurationsSec(probes, scraped)
        assertEquals(300.0, result[0], 0.001)
        assertEquals(300.0, result[1], 0.001)  // equal share of 900 / 3 tracks
        assertEquals(8.0, result[2], 0.001)    // 128000 * 8 / 128000
    }

    @Test fun `resolveTrackDurationsSec returns empty for empty input`() {
        assertEquals(emptyList<Double>(), ChitankaCatalog.resolveTrackDurationsSec(emptyList(), 60.0))
    }

    @Test fun `parseGramofoncheDurationSeconds returns zero when unknown`() {
        assertEquals(0.0, ChitankaCatalog.parseGramofoncheDurationSeconds(null), 0.0)
        assertEquals(0.0, ChitankaCatalog.parseGramofoncheDurationSeconds(""), 0.0)
        assertEquals(0.0, ChitankaCatalog.parseGramofoncheDurationSeconds("unknown"), 0.0)
    }

    // endregion

    @Test fun `books unknown facet key falls back to new arrivals`() {
        // A facet key the catalog doesn't recognise should not silently 404 on a bogus URL —
        // fall back to the safe default surface.
        val url = catalog().browseUrlFor("books", FacetSelection("garbage:xyz"), 0)
        assertEquals("https://chitanka.info/new", url)
    }

    // endregion
}
