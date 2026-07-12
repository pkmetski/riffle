package com.riffle.core.catalog.komga

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.SortKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [KomgaCatalog] reads Komga's REST responses correctly, sends the HTTP Basic auth
 * header on every request, and produces [CatalogItem]s with the right ebookFormat mapping.
 */
class KomgaCatalogTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: KomgaCatalog

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val ok = OkHttpClient()
        val header = buildBasicAuthHeader("user", "pass")
        val config = KomgaCatalogConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            basicAuthHeader = header,
            insecureAllowed = true,
        )
        catalog = KomgaCatalog(config, KomgaHttpClient(ok, header), ok)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `listRoots maps Komga libraries to CatalogRoot`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [
              {"id":"L1","name":"Comics","unavailable":false},
              {"id":"L2","name":"Manga","unavailable":false}
            ]
        """.trimIndent()))

        val roots = catalog.listRoots()

        assertEquals(2, roots.size)
        assertEquals("L1", roots[0].id)
        assertEquals("Comics", roots[0].name)
        assertEquals("book", roots[0].mediaType)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/libraries", recorded.path)
        assertEquals("Basic ${java.util.Base64.getEncoder().encodeToString("user:pass".toByteArray())}",
            recorded.getHeader("Authorization"))
    }

    @Test fun `browse decodes paged books and maps mediaType`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "content": [
                {"id":"B1","libraryId":"L1","name":"book.epub","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"Book One","authors":[{"name":"Alice","role":"writer"}]}},
                {"id":"B2","libraryId":"L1","name":"volume.cbz","media":{"mediaType":"application/x-cbz"},"metadata":{"title":"Vol 2","authors":[{"name":"Bob","role":"writer"}]}},
                {"id":"B3","libraryId":"L1","name":"doc.pdf","media":{"mediaType":"application/pdf"},"metadata":{"title":"PDF","authors":[]}}
              ],
              "totalPages":1,"totalElements":3,"first":true,"last":true,"empty":false
            }
        """.trimIndent()))

        val items = catalog.browse("L1", SortKey.TITLE)

        assertEquals(3, items.size)
        assertEquals("Book One", items[0].title)
        assertEquals("Alice", items[0].author)
        assertEquals(BookFormat.Epub, items[0].ebookFormat)
        assertEquals(BookFormat.Cbz, items[1].ebookFormat)
        assertEquals(BookFormat.Pdf, items[2].ebookFormat)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/v1/books?library_id=L1"))
    }

    @Test fun `fetchFile returns Stream handle with Authorization header baked in`() = runTest {
        val handle = catalog.fetchFile("B1", BookFormat.Epub)

        assertTrue(handle is CatalogFileHandle.Stream)
        val stream = handle as CatalogFileHandle.Stream
        assertEquals(BookFormat.Epub, stream.format)
        assertTrue(stream.url.endsWith("/api/v1/books/B1/file"))
        assertNotNull(stream.headers["Authorization"])
        assertTrue(stream.headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test fun `getItem returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val item = catalog.getItem("missing")

        assertNull(item)
    }

    @Test fun `mediaType mapping is exhaustive`() {
        with(KomgaCatalog) {
            assertEquals(BookFormat.Epub, "application/epub+zip".toBookFormat())
            assertEquals(BookFormat.Pdf, "application/pdf".toBookFormat())
            assertEquals(BookFormat.Cbz, "application/x-cbz".toBookFormat())
            assertEquals(BookFormat.Cbz, "application/vnd.comicbook+zip".toBookFormat())
            assertEquals(BookFormat.Unsupported, "application/octet-stream".toBookFormat())
            assertEquals(BookFormat.Unsupported, (null as String?).toBookFormat())
        }
    }

    // Regression: Komga stores CBZ media with mediaType="application/zip" and
    // mediaProfile="DIVINA"; mediaType alone would treat every CBZ as Unsupported and grey out the
    // whole Comics library. Assertions here would flip red if the mediaProfile dispatch is removed.
    @Test fun `bookFormatFor keys on mediaProfile over mediaType`() {
        with(KomgaCatalog) {
            assertEquals(BookFormat.Cbz, bookFormatFor(KomgaBookMediaDto(mediaType = "application/zip", mediaProfile = "DIVINA")))
            assertEquals(BookFormat.Epub, bookFormatFor(KomgaBookMediaDto(mediaType = "application/epub+zip", mediaProfile = "EPUB")))
            assertEquals(BookFormat.Pdf, bookFormatFor(KomgaBookMediaDto(mediaType = "application/pdf", mediaProfile = "PDF")))
            // Older Komga without mediaProfile falls back to mediaType.
            assertEquals(BookFormat.Epub, bookFormatFor(KomgaBookMediaDto(mediaType = "application/epub+zip")))
            assertEquals(BookFormat.Unsupported, bookFormatFor(KomgaBookMediaDto(mediaType = "application/zip")))
        }
    }

    // Regression: KomgaBookDto.toCatalogItem was dropping Komga's `created` field
    // (addedAt = null). LibraryRepositoryImpl.refreshLibraryItems then stamped `clock.nowMs()`
    // and LibraryItemDao.updateMetadata overwrote the column on every refresh — every book got a
    // fresh ~now, all timestamps tied, ORDER BY addedAt DESC tie-broke by insertion order, and
    // the top-5 Recently Added visibly reshuffled across library opens. The Komga API returns
    // `created` as ISO-8601 (`2026-06-06T19:20:23Z`) — parse it into the CatalogItem's addedAt so
    // the sort key is stable. This assertion would flip red if the parse-through were reverted.
    @Test fun `browse populates addedAt from Komga's created timestamp`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "content": [
                {"id":"B1","libraryId":"L1","name":"one.epub","created":"2026-06-06T19:20:23Z","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"One","authors":[]}},
                {"id":"B2","libraryId":"L1","name":"two.epub","created":"2025-09-14T14:05:16Z","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"Two","authors":[]}},
                {"id":"B3","libraryId":"L1","name":"three.epub","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"Three","authors":[]}}
              ],
              "totalPages":1,"totalElements":3,"first":true,"last":true,"empty":false
            }
        """.trimIndent()))

        val items = catalog.browse("L1", SortKey.TITLE)

        // 2026-06-06T19:20:23Z → epoch ms
        assertEquals(java.time.Instant.parse("2026-06-06T19:20:23Z").toEpochMilli(), items[0].addedAt)
        assertEquals(java.time.Instant.parse("2025-09-14T14:05:16Z").toEpochMilli(), items[1].addedAt)
        // No `created` field on the book → null; the repository stamps a `now` fallback.
        assertNull(items[2].addedAt)
    }

    @Test fun `parseIsoInstant handles null blank and malformed strings`() {
        assertNull(KomgaCatalog.parseIsoInstant(null))
        assertNull(KomgaCatalog.parseIsoInstant(""))
        assertNull(KomgaCatalog.parseIsoInstant("   "))
        assertNull(KomgaCatalog.parseIsoInstant("not-a-timestamp"))
        assertEquals(0L, KomgaCatalog.parseIsoInstant("1970-01-01T00:00:00Z"))
    }

    // region ProgressPeerCapability (#528) — page-based read-progress round-trip

    @Test fun `cfiDialect is PAGE_NUMBER so the sweep bypasses CFI translation`() {
        // A regression pin — CatalogEbookProgressRemote gates translator invocation on this. If a
        // later change flipped the default back to EPUB_JS, Komga's page-shaped position strings
        // would be handed to the CFI translator, which would return null and mark every push a
        // no-op. This assertion flips red under exactly that regression.
        assertEquals(CfiDialect.PAGE_NUMBER, catalog.cfiDialect)
    }

    @Test fun `pushEbookProgress PATCHes read-progress with the decoded page number`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.pushEbookProgress(
            itemId = "B1",
            location = "42",
            progress = 0.5f,
            isFinished = null,
            lastUpdateEpochMs = 1_700_000L,
        )

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/books/B1/read-progress", recorded.path)
        val bodyStr = recorded.body.readUtf8()
        // page decoded; completed derived from progress<1
        assertTrue("page must be 42: $bodyStr", bodyStr.contains("\"page\":42"))
        assertTrue("completed must be false: $bodyStr", bodyStr.contains("\"completed\":false"))
    }

    @Test fun `pushEbookProgress forwards explicit isFinished=true (mark-finished)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.pushEbookProgress(
            itemId = "B1",
            location = "10",
            progress = 1f,
            isFinished = true,
            lastUpdateEpochMs = 0L,
        )

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"completed\":true"))
    }

    @Test fun `pushEbookProgress decodes the page from Riffle's Readium Locator JSON (position field)`() = runTest {
        // The PDF reader's persisted locator carries `locations.position` (1-indexed page). This
        // pins the boundary translator: without it, every Komga PDF push was a no-op (regression
        // caught on-device during #528 verification).
        server.enqueue(MockResponse().setResponseCode(204))
        val readerLocator = """{"href":"publication.pdf","type":"application/pdf",""" +
            """"locations":{"position":18,"fragments":["page=18"],"progression":0.032}}"""

        catalog.pushEbookProgress(
            itemId = "B1",
            location = readerLocator,
            progress = 0.032f,
            isFinished = null,
            lastUpdateEpochMs = 0L,
        )

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue("page must decode to 18: $bodyStr", bodyStr.contains("\"page\":18"))
    }

    @Test fun `pushEbookProgress falls back to fragments page=N when locations position absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val readerLocator = """{"locations":{"fragments":["page=7"]}}"""

        catalog.pushEbookProgress(
            itemId = "B1",
            location = readerLocator,
            progress = 0.0f,
            isFinished = null,
            lastUpdateEpochMs = 0L,
        )

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue("page must decode to 7: $bodyStr", bodyStr.contains("\"page\":7"))
    }

    @Test fun `pushEbookProgress with a non-numeric location returns null without hitting Komga`() = runTest {
        // A Readium Locator JSON without any page marker (e.g. a Komga-backed EPUB whose reader
        // stores only href + progression) can't be decoded to a page — the push must be a no-op,
        // not a PATCH with all-null fields (which Komga rejects).
        val undecodable = """{"href":"OEBPS/ch1.xhtml","locations":{"progression":0.5}}"""

        val stamp = catalog.pushEbookProgress(
            itemId = "B1",
            location = undecodable,
            progress = 0.5f,
            isFinished = null,
            lastUpdateEpochMs = 0L,
        )

        assertNull(stamp)
        assertEquals("no PATCH must reach Komga for an undecodable position", 0, server.requestCount)
    }

    @Test fun `pullProgress reads the book's readProgress record into CatalogProgress`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"B1","libraryId":"L1","name":"book","media":{"mediaType":"application/pdf","pagesCount":200,"mediaProfile":"PDF"},
             "metadata":{"title":"Book","authors":[]},
             "readProgress":{"page":50,"completed":false,"readDate":"2026-07-01T10:00:00Z","lastModified":"2026-07-01T10:00:00Z"}}
        """.trimIndent()))

        val p = catalog.pullProgress("B1")

        assertNotNull(p)
        assertEquals("B1", p!!.itemId)
        // ebookLocation carries a Readium Locator JSON — the reader consumes it directly on
        // ServerWins without any translation. Assert on the shape the PDF/CBZ Navigator reads
        // (`locations.position` + `fragments=["page=N"]`).
        val loc = p.ebookLocation!!
        assertTrue("must be a Locator JSON, was: $loc", loc.startsWith("{"))
        assertTrue("must carry position=50: $loc", loc.contains("\"position\":50"))
        assertTrue("must carry page=50 fragment: $loc", loc.contains("\"page=50\""))
        assertEquals(0.25f, p.ebookProgress, 1e-6f)
        assertEquals(false, p.isFinished)
        assertEquals(java.time.Instant.parse("2026-07-01T10:00:00Z").toEpochMilli(), p.lastUpdate)
    }

    @Test fun `pullProgress round-trips through pushEbookProgress — the Locator JSON decodes back to the same page`() = runTest {
        // Guards the boundary translator symmetry: whatever pullProgress emits must be a valid
        // input to pushEbookProgress, so the sweep can PATCH the ServerWins position back without
        // losing the page.
        server.enqueue(MockResponse().setBody("""
            {"id":"B1","libraryId":"L1","name":"book","media":{"pagesCount":500,"mediaProfile":"PDF"},
             "metadata":{"title":"Book","authors":[]},
             "readProgress":{"page":123,"completed":false,"lastModified":"2026-07-01T10:00:00Z"}}
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(204))

        val pulled = catalog.pullProgress("B1")!!
        catalog.pushEbookProgress("B1", pulled.ebookLocation!!, pulled.ebookProgress, null, 0L)

        server.takeRequest() // GET /books/B1
        val patchBody = server.takeRequest().body.readUtf8()
        assertTrue("push must preserve page=123: $patchBody", patchBody.contains("\"page\":123"))
    }

    @Test fun `pullProgress marks completed books as finished`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"B1","libraryId":"L1","name":"book","media":{"pagesCount":200},"metadata":{"title":"Book","authors":[]},
             "readProgress":{"page":200,"completed":true,"readDate":"2026-07-01T10:00:00Z","lastModified":"2026-07-01T10:00:00Z"}}
        """.trimIndent()))

        val p = catalog.pullProgress("B1")!!
        assertTrue(p.isFinished)
        assertEquals(1f, p.ebookProgress, 1e-6f)
        assertNotNull(p.finishedAt)
    }

    @Test fun `pullProgress returns a record even when the book has no progress (never-opened)`() = runTest {
        // No readProgress field → dto.readProgress == null → toCatalogProgress → null.
        server.enqueue(MockResponse().setBody("""
            {"id":"B1","libraryId":"L1","name":"book","media":{"pagesCount":200},"metadata":{"title":"Book","authors":[]}}
        """.trimIndent()))

        assertNull("a book with no server-side progress has no CatalogProgress row", catalog.pullProgress("B1"))
    }

    @Test fun `pullAllProgress sweeps every page of in-progress books`() = runTest {
        // Two pages back-to-back; the second is marked last=true.
        server.enqueue(MockResponse().setBody("""
            {"content":[
              {"id":"B1","libraryId":"L1","name":"a","media":{"pagesCount":100},"metadata":{"title":"A","authors":[]},
               "readProgress":{"page":10,"completed":false,"lastModified":"2026-07-01T10:00:00Z"}}
            ],"last":false,"totalPages":2,"totalElements":2,"empty":false,"first":true,"number":0}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"content":[
              {"id":"B2","libraryId":"L1","name":"b","media":{"pagesCount":50},"metadata":{"title":"B","authors":[]},
               "readProgress":{"page":50,"completed":true,"lastModified":"2026-07-02T10:00:00Z"}}
            ],"last":true,"totalPages":2,"totalElements":2,"empty":false,"first":false,"number":1}
        """.trimIndent()))

        val all = catalog.pullAllProgress()

        assertEquals(2, all.size)
        assertEquals("B1", all[0].itemId)
        assertEquals(0.1f, all[0].ebookProgress, 1e-6f)
        assertEquals("B2", all[1].itemId)
        assertTrue(all[1].isFinished)
    }

    // endregion

    @Test fun `parseActuatorVersion extracts build version`() {
        val v = KomgaCatalog.parseActuatorVersion(
            """{"build":{"version":"1.10.0","artifact":"komga"}}"""
        )
        assertEquals("1.10.0", v)
        assertNull(KomgaCatalog.parseActuatorVersion("{}"))
        assertNull(KomgaCatalog.parseActuatorVersion("not-json"))
    }
}
