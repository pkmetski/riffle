package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KomgaCbzApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KomgaLibraryApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KomgaLibraryApiClient(createDefaultHttpClient(OkHttpClient()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `fetchCbzPageCount calls correct URL and parses pagesCount`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"book1","media":{"pagesCount":42,"status":"READY"}}""")
                .addHeader("Content-Type", "application/json"),
        )
        val count = client.fetchCbzPageCount(
            baseUrl = baseUrl(),
            bookId = "book1",
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        assertEquals(42, count)
        assertEquals("/api/v1/books/book1", server.takeRequest().path)
    }

    @Test
    fun `fetchCbzPage uses 1-based page number in URL`() = runTest {
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().apply { write(imageBytes) }),
        )
        client.fetchCbzPage(
            baseUrl = baseUrl(),
            bookId = "book2",
            pageIndex = 0,
            maxWidth = null,
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        assertEquals("/api/v1/books/book2/pages/1", server.takeRequest().path)
    }

    @Test
    fun `fetchCbzPage appends width param when maxWidth is set`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().apply { write(byteArrayOf(1, 2, 3)) }),
        )
        client.fetchCbzPage(
            baseUrl = baseUrl(),
            bookId = "book3",
            pageIndex = 4,
            maxWidth = 300,
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        assertEquals("/api/v1/books/book3/pages/5?width=300", server.takeRequest().path)
    }
}
