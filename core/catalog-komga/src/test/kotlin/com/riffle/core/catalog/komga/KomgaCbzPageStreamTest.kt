package com.riffle.core.catalog.komga

import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.catalog.has
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KomgaCbzPageStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: KomgaCatalog

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val header = buildBasicAuthHeader("user", "pass")
        val config = KomgaCatalogConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            basicAuthHeader = header,
            insecureAllowed = true,
        )
        val httpClient = HttpClient(OkHttp) {}
        catalog = KomgaCatalog(config, KomgaHttpClient(httpClient, header), httpClient)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `KomgaCatalog declares CbzPageStreamCapability`() {
        assertTrue(catalog.has<CbzPageStreamCapability>())
    }

    @Test fun `fetchCbzPageImage calls correct URL and returns bytes`() = runTest {
        val fakeBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeBytes))
        )

        val cap = catalog as CbzPageStreamCapability
        val result = cap.fetchCbzPageImage("BOOK1", 0)

        val request = server.takeRequest()
        assertEquals("/api/v1/books/BOOK1/pages/1", request.path)
        assertArrayEquals(fakeBytes, result)
    }

    @Test fun `fetchCbzPageImage converts 0-based pageIndex to 1-based URL`() = runTest {
        val fakeBytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeBytes))
        )

        val cap = catalog as CbzPageStreamCapability
        cap.fetchCbzPageImage("B1", 4) // pageIndex 4 → page 5

        val request = server.takeRequest()
        assertEquals("/api/v1/books/B1/pages/5", request.path)
    }

    @Test fun `fetchCbzPageImage appends width query param when maxWidth is provided`() = runTest {
        val fakeBytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeBytes))
        )

        val cap = catalog as CbzPageStreamCapability
        cap.fetchCbzPageImage("B1", 0, maxWidth = 300)

        val request = server.takeRequest()
        assertEquals("/api/v1/books/B1/pages/1?width=300", request.path)
    }

    @Test fun `fetchCbzPageImage omits width query param when maxWidth is null`() = runTest {
        val fakeBytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeBytes))
        )

        val cap = catalog as CbzPageStreamCapability
        cap.fetchCbzPageImage("B1", 0, maxWidth = null)

        val request = server.takeRequest()
        assertEquals("/api/v1/books/B1/pages/1", request.path)
    }

    @Test fun `fetchCbzPageCount returns pagesCount from book metadata`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "id": "B1",
              "libraryId": "L1",
              "name": "book.cbz",
              "media": {"mediaType": "application/x-cbz", "pagesCount": 42},
              "metadata": {"title": "My Comic", "authors": []}
            }
        """.trimIndent()))

        val cap = catalog as CbzPageStreamCapability
        val count = cap.fetchCbzPageCount("B1")

        assertEquals(42, count)
        val request = server.takeRequest()
        assertEquals("/api/v1/books/B1", request.path)
    }

    @Test fun `fetchCbzPageCount returns 0 when pagesCount absent`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "id": "B1",
              "libraryId": "L1",
              "name": "book.cbz",
              "media": {"mediaType": "application/x-cbz"},
              "metadata": {"title": "My Comic", "authors": []}
            }
        """.trimIndent()))

        val cap = catalog as CbzPageStreamCapability
        val count = cap.fetchCbzPageCount("B1")

        assertEquals(0, count)
    }
}
