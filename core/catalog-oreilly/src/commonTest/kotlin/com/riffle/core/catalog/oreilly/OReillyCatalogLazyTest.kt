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

/**
 * Tests for [OReillyCatalog.lazyPublication], [OReillyCatalog.fetchChapterForLazy], and
 * [OReillyCatalog.fetchAssetForLazy]. Verifies the lazy-open metadata path returns a
 * correctly-ordered spine with sizes and CSS paths — no chapter HTML is downloaded.
 */
class OReillyCatalogLazyTest {

    private val BASE_URL = "http://test"
    private val itemId = "9781234567890"
    private val urn = "urn:orm:book:$itemId"

    private lateinit var client: HttpClient
    private lateinit var catalog: OReillyCatalog

    @BeforeTest
    fun setUp() {
        val engine = MockEngine { request ->
            val pathAndQuery = request.url.encodedPathAndQuery
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                pathAndQuery.startsWith("/api/v2/epubs/$urn/spine/") -> respond(
                    """{
                        "count":2,"next":null,"results":[
                          {"reference_id":"$itemId-/xhtml/ch01.xhtml","title":"Chapter 1"},
                          {"reference_id":"$itemId-/xhtml/ch02.xhtml","title":"Chapter 2"}
                        ]
                    }""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                pathAndQuery.startsWith("/api/v2/epubs/$urn/files/") -> respond(
                    """{
                        "count":3,"next":null,"results":[
                          {"full_path":"xhtml/ch01.xhtml","media_type":"application/xhtml+xml","kind":"chapter","file_size":50000},
                          {"full_path":"xhtml/ch02.xhtml","media_type":"application/xhtml+xml","kind":"chapter","file_size":30000},
                          {"full_path":"styles/main.css","media_type":"text/css","kind":"stylesheet","file_size":5000}
                        ]
                    }""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(
                    """{"identifier":"$itemId","title":"Test Book","language":"en"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
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
    fun `lazyPublication returns spine in reading order with declared sizes`() = runTest {
        val pub = catalog.lazyPublication(itemId)

        assertNotNull(pub)
        assertEquals(itemId, pub!!.bookId)
        assertEquals(pub.title, "Test Book")
        assertEquals(pub.language, "en")
        assertEquals(2, pub.spine.size)
        assertEquals(pub.spine[0].fullPath, "xhtml/ch01.xhtml")
        assertEquals(pub.spine[0].title, "Chapter 1")
        assertEquals(50_000L, pub.spine[0].declaredByteSize)
        assertEquals(0, pub.spine[0].index)
        assertEquals(pub.spine[1].fullPath, "xhtml/ch02.xhtml")
        assertEquals(30_000L, pub.spine[1].declaredByteSize)
        assertEquals(1, pub.spine[1].index)
    }

    @Test
    fun `lazyPublication includes stylesheet paths in cssFullPaths`() = runTest {
        val pub = catalog.lazyPublication(itemId)
        assertNotNull(pub)
        assertEquals(listOf("styles/main.css"), pub!!.cssFullPaths)
    }

    @Test
    fun `lazyPublication sets absoluteFilesPrefix and pathFilesPrefix correctly`() = runTest {
        val pub = catalog.lazyPublication(itemId)
        assertNotNull(pub)
        assertEquals(pub!!.absoluteFilesPrefix, "$BASE_URL/api/v2/epubs/$urn/files/")
        assertEquals(pub.pathFilesPrefix, "/api/v2/epubs/$urn/files/")
    }

    @Test
    fun `fetchChapterForLazy does not call the files-listing endpoint`() = runTest {
        // Use an engine where the files-listing endpoint returns 500. If fetchChapterForLazy
        // still called fetchAllFiles (the old bug), the whole call would fail and return null.
        val localEngine = MockEngine { request ->
            val pathAndQuery = request.url.encodedPathAndQuery
            when {
                // Chapter download endpoint — serves content
                pathAndQuery.contains("?download=false") ->
                    respond("<p>Hello</p>", HttpStatusCode.OK)
                // Files-listing endpoint — fail hard so the test catches any regression
                pathAndQuery.startsWith("/api/v2/epubs/$urn/files/") ->
                    respond("should not be called", HttpStatusCode.InternalServerError)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val localClient = HttpClient(localEngine)
        val api = OReillyApi(localClient, cookieHeader = "orm-jwt=x", baseUrl = BASE_URL)
        val localCatalog = OReillyCatalog(api = api, bytesClient = localClient, cookieHeader = "orm-jwt=x")

        // expectedByteSize = 0 skips truncation detection; we only care that no files-listing
        // request was made, not about truncation handling.
        val result = localCatalog.fetchChapterForLazy(itemId, "xhtml/ch01.xhtml", expectedByteSize = 0L)
        assertNotNull(result, "fetchChapterForLazy should succeed without calling the files endpoint")

        localClient.close()
    }

    @Test
    fun `lazyPublication follows spine pagination and returns all chapters`() = runTest {
        // Regression: spineUrl hardcoded limit=100 and callers never followed the next cursor.
        // Serve 2 pages of 2 chapters each; lazyPublication must return 4 spine items.
        val page2Path = "/api/v2/epubs/$urn/spine/?limit=100&offset=2"
        val paginationEngine = MockEngine { request ->
            val pathAndQuery = request.url.encodedPathAndQuery
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                pathAndQuery.contains("/spine/") && !pathAndQuery.contains("offset") ->
                    respond(
                        """{"count":4,"next":"$BASE_URL$page2Path","results":[
                          {"reference_id":"$itemId-/xhtml/ch01.xhtml","title":"Chapter 1"},
                          {"reference_id":"$itemId-/xhtml/ch02.xhtml","title":"Chapter 2"}
                        ]}""".trimIndent(),
                        HttpStatusCode.OK, jsonHeaders,
                    )
                pathAndQuery.contains("offset=2") ->
                    respond(
                        """{"count":4,"next":null,"results":[
                          {"reference_id":"$itemId-/xhtml/ch03.xhtml","title":"Chapter 3"},
                          {"reference_id":"$itemId-/xhtml/ch04.xhtml","title":"Chapter 4"}
                        ]}""".trimIndent(),
                        HttpStatusCode.OK, jsonHeaders,
                    )
                pathAndQuery.contains("/files/") ->
                    respond("""{"count":0,"next":null,"results":[]}""", HttpStatusCode.OK, jsonHeaders)
                else ->
                    respond(
                        """{"identifier":"$itemId","title":"Big Book","language":"en"}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
            }
        }
        val paginationClient = HttpClient(paginationEngine)
        val api = OReillyApi(paginationClient, cookieHeader = "orm-jwt=x", baseUrl = BASE_URL)
        val paginationCatalog = OReillyCatalog(api = api, bytesClient = paginationClient, cookieHeader = "orm-jwt=x")

        val pub = paginationCatalog.lazyPublication(itemId)
        assertEquals(4, pub?.spine?.size, "Should return all 4 chapters from both spine pages")
        assertEquals(pub?.spine?.last()?.fullPath, "xhtml/ch04.xhtml")

        paginationClient.close()
    }
}
