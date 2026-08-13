package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val httpClient = HttpClient(OkHttp) {}
        val http = GutenbergHttpClient(client = httpClient, userAgent = "Riffle/test", retryDelaysMs = emptyList())
        catalog = GutenbergCatalog(
            http = http,
            bytesClient = httpClient,
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
        assertEquals("Books", roots.single().name)
    }

    @Test
    fun `listFacets returns the curated topic set for the books root`() = runTest {
        val facets = catalog.listFacets(GutenbergCatalog.ROOT_BOOKS)
        assertTrue("expected curated topics, got $facets", facets.isNotEmpty())
        assertTrue(facets.any { it.key == "topic:fiction" })
    }

    @Test
    fun `listFacets returns the curated language filters for the books root`() = runTest {
        val facets = catalog.listFacets(GutenbergCatalog.ROOT_BOOKS)
        assertTrue("English language facet missing from $facets", facets.any { it.key == "language:en" })
        assertTrue("French language facet missing from $facets", facets.any { it.key == "language:fr" })
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
    fun `browse threads the language facet into the query string`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-books-page-1.json")))
        catalog.browse(
            rootId = GutenbergCatalog.ROOT_BOOKS,
            page = 0,
            pageSize = 32,
            facet = FacetSelection("language:fr"),
        )
        val request = server.takeRequest()
        assertTrue("expected languages=fr in ${request.path}", request.path?.contains("languages=fr") == true)
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
    fun `faceted search threads the language facet into the query string`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("gutendex-books-page-1.json")))
        catalog.search(
            rootId = GutenbergCatalog.ROOT_BOOKS,
            query = "dumas",
            page = 0,
            pageSize = 32,
            facet = FacetSelection("language:fr"),
        )
        val request = server.takeRequest()
        val path = request.path.orEmpty()
        assertTrue("expected search=dumas in $path", path.contains("search=dumas"))
        assertTrue("expected languages=fr in $path", path.contains("languages=fr"))
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

    @Test
    fun `download exposes EPUB bytes before the response finishes`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(64 * 1024, 1, TimeUnit.SECONDS),
        )
        val firstRead = CompletableDeferred<Int>()

        val request = async(Dispatchers.IO) {
            catalog.withBytesWithRetry(server.url("/book.epub").toString(), itemId = "1342") { stream ->
                val input = stream.byteStream()
                val buffer = ByteArray(64 * 1024)
                firstRead.complete(input.read(buffer))
                while (input.read(buffer) >= 0) {
                    // Drain the live response.
                }
            }
        }

        assertTrue(withTimeout(1_500) { firstRead.await() } > 0)
        assertFalse("response should still be receiving throttled bytes", request.isCompleted)
        request.await()
    }
}
