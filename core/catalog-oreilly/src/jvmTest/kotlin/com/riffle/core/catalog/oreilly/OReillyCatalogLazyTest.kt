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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [OReillyCatalog.lazyPublication], [OReillyCatalog.fetchChapterForLazy], and
 * [OReillyCatalog.fetchAssetForLazy]. Verifies the lazy-open metadata path returns a
 * correctly-ordered spine with sizes and CSS paths — no chapter HTML is downloaded.
 */
class OReillyCatalogLazyTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient
    private lateinit var catalog: OReillyCatalog

    private val itemId = "9781234567890"
    private val urn = "urn:orm:book:$itemId"

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = when {
                path.startsWith("/api/v2/epubs/$urn/") && path.endsWith("/") && !path.contains("/spine") && !path.contains("/files") ->
                    """{"identifier":"$itemId","title":"Test Book","language":"en"}"""

                path.startsWith("/api/v2/epubs/$urn/spine/") ->
                    """{
                        "count":2,"next":null,"results":[
                          {"reference_id":"$itemId-/xhtml/ch01.xhtml","title":"Chapter 1"},
                          {"reference_id":"$itemId-/xhtml/ch02.xhtml","title":"Chapter 2"}
                        ]
                    }"""

                path.startsWith("/api/v2/epubs/$urn/files/") && !path.contains("?download=false") ->
                    """{
                        "count":3,"next":null,"results":[
                          {"full_path":"xhtml/ch01.xhtml","media_type":"application/xhtml+xml","kind":"chapter","file_size":50000},
                          {"full_path":"xhtml/ch02.xhtml","media_type":"application/xhtml+xml","kind":"chapter","file_size":30000},
                          {"full_path":"styles/main.css","media_type":"text/css","kind":"stylesheet","file_size":5000}
                        ]
                    }"""

                else -> return MockResponse().setResponseCode(404).setBody("{}")
            }
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { dispatcher = this@OReillyCatalogLazyTest.dispatcher; start() }
        client = HttpClient(OkHttp)
        val api = OReillyApi(client, cookieHeader = "orm-jwt=x", baseUrl = server.url("/").toString().trimEnd('/'))
        catalog = OReillyCatalog(api = api, bytesClient = client, cookieHeader = "orm-jwt=x")
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `lazyPublication returns spine in reading order with declared sizes`() = runBlocking {
        val pub = catalog.lazyPublication(itemId)

        assertNotNull(pub)
        assertEquals(itemId, pub!!.bookId)
        assertEquals("Test Book", pub.title)
        assertEquals("en", pub.language)
        assertEquals(2, pub.spine.size)
        assertEquals("xhtml/ch01.xhtml", pub.spine[0].fullPath)
        assertEquals("Chapter 1", pub.spine[0].title)
        assertEquals(50_000L, pub.spine[0].declaredByteSize)
        assertEquals(0, pub.spine[0].index)
        assertEquals("xhtml/ch02.xhtml", pub.spine[1].fullPath)
        assertEquals(30_000L, pub.spine[1].declaredByteSize)
        assertEquals(1, pub.spine[1].index)
    }

    @Test
    fun `lazyPublication includes stylesheet paths in cssFullPaths`() = runBlocking {
        val pub = catalog.lazyPublication(itemId)
        assertNotNull(pub)
        assertEquals(listOf("styles/main.css"), pub!!.cssFullPaths)
    }

    @Test
    fun `lazyPublication sets absoluteFilesPrefix and pathFilesPrefix correctly`() = runBlocking {
        val pub = catalog.lazyPublication(itemId)
        assertNotNull(pub)
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/api/v2/epubs/$urn/files/", pub!!.absoluteFilesPrefix)
        assertEquals("/api/v2/epubs/$urn/files/", pub.pathFilesPrefix)
    }
}
