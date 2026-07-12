package com.riffle.core.catalog.komga

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
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

    @Test fun `parseActuatorVersion extracts build version`() {
        val v = KomgaCatalog.parseActuatorVersion(
            """{"build":{"version":"1.10.0","artifact":"komga"}}"""
        )
        assertEquals("1.10.0", v)
        assertNull(KomgaCatalog.parseActuatorVersion("{}"))
        assertNull(KomgaCatalog.parseActuatorVersion("not-json"))
    }
}
