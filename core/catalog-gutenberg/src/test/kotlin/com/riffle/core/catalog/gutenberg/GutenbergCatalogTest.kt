package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GutenbergCatalogTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: GutenbergCatalog

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val okHttp = OkHttpClient()
        val http = GutenbergHttpClient(client = okHttp, userAgent = "Riffle/test", retryDelaysMs = emptyList())
        catalog = GutenbergCatalog(
            http = http,
            bytesClient = okHttp,
            userAgent = "Riffle/test",
            apiBase = server.url("").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() { server.shutdown() }

    // ---- Contract ------------------------------------------------------------

    @Test
    fun `sourceType is GUTENBERG`() {
        assertEquals(SourceType.GUTENBERG, catalog.sourceType)
    }

    @Test
    fun `listRoots returns a single Books root`() = runTest {
        val roots = catalog.listRoots()
        assertEquals(1, roots.size)
        assertEquals(GutenbergCatalog.ROOT_BOOKS, roots.single().id)
    }

    @Test
    fun `listFacets returns the curated topic set for the books root`() = runTest {
        val facets = catalog.listFacets(GutenbergCatalog.ROOT_BOOKS)
        assertTrue("expected curated topics, got $facets", facets.isNotEmpty())
        assertTrue(facets.all { it.key.startsWith("topic:") })
    }

    @Test
    fun `listFacets is empty for unknown roots`() = runTest {
        assertTrue(catalog.listFacets("bogus").isEmpty())
    }

    // ---- Browse --------------------------------------------------------------

    @Test
    fun `browse hits page=1 for page 0 and maps items to CatalogItem`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-books-page-1.json")))
        val items = catalog.browse(rootId = GutenbergCatalog.ROOT_BOOKS, page = 0, pageSize = 32)
        val request = server.takeRequest()
        assertTrue(
            "expected page=1 in $request",
            request.path?.contains("page=1") == true,
        )
        assertEquals(3, items.size)
        val pride = items.first { it.id == "1342" }
        assertEquals("Pride and Prejudice", pride.title)
        assertEquals("Austen, Jane", pride.author)
        assertEquals(BookFormat.Epub, pride.ebookFormat)
        assertEquals("English", pride.language)
    }

    @Test
    fun `browse threads the topic facet into the query string`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-books-page-1.json")))
        catalog.browse(
            rootId = GutenbergCatalog.ROOT_BOOKS,
            page = 0,
            pageSize = 32,
            facet = FacetSelection("topic:fiction"),
        )
        val request = server.takeRequest()
        assertTrue("expected topic=fiction in ${request.path}", request.path?.contains("topic=fiction") == true)
    }

    @Test
    fun `browse for a non-books root returns empty without hitting the network`() = runTest {
        val items = catalog.browse(rootId = "bogus", page = 0, pageSize = 32)
        assertTrue(items.isEmpty())
        assertEquals(0, server.requestCount)
    }

    // ---- Search --------------------------------------------------------------

    @Test
    fun `search sends the query as URL-encoded`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-books-page-1.json")))
        catalog.search(rootId = GutenbergCatalog.ROOT_BOOKS, query = "jane austen", page = 0, pageSize = 32)
        val request = server.takeRequest()
        // Space encodes as `+` under `application/x-www-form-urlencoded` semantics used by
        // URLEncoder — accept either form.
        val path = request.path.orEmpty()
        assertTrue(
            "expected encoded query in $path",
            path.contains("search=jane+austen") || path.contains("search=jane%20austen"),
        )
    }

    @Test
    fun `search with blank query returns empty and does not hit the network`() = runTest {
        val items = catalog.search(rootId = GutenbergCatalog.ROOT_BOOKS, query = "   ", page = 0, pageSize = 32)
        assertTrue(items.isEmpty())
        assertEquals(0, server.requestCount)
    }

    // ---- Item lookup ---------------------------------------------------------

    @Test
    fun `getItem resolves numeric ids via the detail endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-book-detail.json")))
        val item = catalog.getItem("1342")
        assertNotNull(item)
        assertEquals("1342", item!!.id)
        assertEquals("Pride and Prejudice", item.title)
        val request = server.takeRequest()
        assertTrue(request.path?.startsWith("/books/1342") == true)
    }

    @Test
    fun `getItem returns null for non-numeric ids`() = runTest {
        assertNull(catalog.getItem("not-a-number"))
        assertEquals(0, server.requestCount)
    }

    // ---- File access ---------------------------------------------------------

    @Test
    fun `fetchFile returns the epub URL from the item formats map`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-book-detail.json")))
        val handle = catalog.fetchFile("1342", BookFormat.Epub)
        assertTrue(handle is CatalogFileHandle.Stream)
        val stream = handle as CatalogFileHandle.Stream
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", stream.url)
    }

    @Test
    fun `fetchFile rejects non-EPUB formats`() = runTest {
        try {
            catalog.fetchFile("1342", BookFormat.Pdf)
            fail("expected exception")
        } catch (_: RuntimeException) {
            // ok
        }
    }
}
