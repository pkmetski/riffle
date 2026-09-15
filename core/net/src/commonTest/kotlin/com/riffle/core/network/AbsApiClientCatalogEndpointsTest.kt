package com.riffle.core.network

import com.riffle.core.models.EbookFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Endpoint-level coverage for the ABS methods introduced in the catalog abstraction (issue #433).
 * The AbsCatalog-level tests use fakes; these pin JSON parsing, URL construction, HTTP verbs, and
 * auth-header wiring at the network layer.
 */
class AbsApiClientCatalogEndpointsTest {

    // region searchLibrary

    @Test fun `searchLibrary parses book hits from grouped response`() = runTest {
        val (_, client) = mockAbsClient(
            """
                {
                  "book": [
                    {"libraryItem": {"id":"b1","libraryId":"lib-a","media":{"metadata":{"title":"Hobbit","authorName":"Tolkien"},"ebookFormat":"epub"}}, "matchKey":"title", "matchText":"Hobbit"},
                    {"libraryItem": {"id":"b2","libraryId":"lib-a","media":{"metadata":{"title":"Silmarillion","authorName":"Tolkien"},"ebookFormat":"epub"}}, "matchKey":"title", "matchText":"Silmarillion"}
                  ],
                  "podcast": [], "authors": [], "tags": [], "series": []
                }
            """.trimIndent() to HttpStatusCode.OK,
        )

        val result = client.searchLibrary(BASE_URL, "lib-a", "tolkien", limit = 10, token = "T", insecureAllowed = false)

        assertTrue(result is NetworkResult.Success)
        val items = (result as NetworkResult.Success).value
        assertEquals(2, items.size)
        assertEquals(items[0].id, "b1")
        assertEquals(items[0].title, "Hobbit")
        assertEquals(EbookFormat.Epub, items[0].ebookFormat)
    }

    @Test fun `searchLibrary URL encodes the query and includes limit`() = runTest {
        val (engine, client) = mockAbsClient("""{"book":[]}""" to HttpStatusCode.OK)

        client.searchLibrary(BASE_URL, "lib-a", "hobbit & rings", limit = 25, token = "T", insecureAllowed = false)

        val req = engine.requestHistory[0]
        assertTrue(
            req.url.encodedPathAndQuery.matches(
                Regex("/api/libraries/lib-a/search\\?q=hobbit\\+%26\\+rings&limit=25&sort=random&_riffle_refresh=-?\\d+"),
            ),
        )
        assertEquals(req.headers["Cache-Control"], "no-cache, no-store")
        assertEquals(req.headers["Authorization"], "Bearer T")
    }

    @Test fun `searchLibrary returns empty list when book group is absent`() = runTest {
        val (_, client) = mockAbsClient("""{}""" to HttpStatusCode.OK)

        val result = client.searchLibrary(BASE_URL, "lib-a", "q", 10, "T", false)

        assertTrue(((result as NetworkResult.Success).value).isEmpty())
    }

    // endregion

    // region getItem

    @Test fun `getItem returns null on 404`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.NotFound)

        val result = client.getItem(BASE_URL, "gone", "T", false)

        assertTrue(result is NetworkResult.Success)
        assertNull((result as NetworkResult.Success).value)
    }

    @Test fun `getItem parses single item envelope`() = runTest {
        val (_, client) = mockAbsClient(
            """
                {"id":"it-1","libraryId":"lib-a","media":{"metadata":{"title":"A","authorName":"B"},"ebookFormat":"epub","ebookFile":{"ino":"ino-42"},"numAudioFiles":0}}
            """.trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getItem(BASE_URL, "it-1", "T", false)

        val item = (result as NetworkResult.Success).value!!
        assertEquals(item.id, "it-1")
        assertEquals(item.title, "A")
        assertEquals(item.ebookFileIno, "ino-42")
    }

    @Test fun `getItem hits expanded item path`() = runTest {
        val (engine, client) = mockAbsClient("" to HttpStatusCode.NotFound)

        client.getItem(BASE_URL, "it-1", "T", false)

        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/items/it-1")
        assertTrue(engine.requestHistory[0].url.encodedPathAndQuery.contains("expanded=1"))
    }

    // endregion

    // region syncPlaybackSession + closePlaybackSession

    @Test fun `syncPlaybackSession posts currentTime and timeListened`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.syncPlaybackSession(BASE_URL, "sess-1", 120.5, 60.0, "T", false)

        assertTrue(result is NetworkResult.Success)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/session/sess-1/sync")
        assertEquals(req.headers["Authorization"], "Bearer T")
        assertTrue(capturedBody.contains("\"currentTime\":120.5"), "body should include currentTime: $capturedBody")
        assertTrue(capturedBody.contains("\"timeListened\":60"), "body should include timeListened: $capturedBody")
    }

    @Test fun `closePlaybackSession hits close endpoint`() = runTest {
        val (engine, client) = mockAbsClient("{}" to HttpStatusCode.OK)

        client.closePlaybackSession(BASE_URL, "sess-1", 200.0, 90.0, "T", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/session/sess-1/close")
    }

    // endregion

    // region uploadBook

    @Test fun `uploadBook sends metadata and source files as multipart`() = runTest {
        var capturedBody = ""
        var capturedAuth = ""
        var capturedPath = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            capturedAuth = request.headers["Authorization"] ?: ""
            capturedPath = request.url.encodedPath
            respond("", HttpStatusCode.Created, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.uploadBook(
            baseUrl = BASE_URL,
            libraryId = "lib-a",
            metadata = NetworkUploadMetadata(
                title = "A title",
                author = "An author",
                folderId = "folder-a",
            ),
            files = listOf(
                NetworkUploadPart("book.epub", "application/epub+zip") {
                    ByteReadChannel("epub-bytes")
                },
            ),
            token = "T",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkResult.Success)
        assertEquals(capturedPath, "/api/upload")
        assertEquals(capturedAuth, "Bearer T")
        assertTrue(capturedBody.contains("name=\"title\""))
        assertTrue(capturedBody.contains("A title"))
        assertTrue(capturedBody.contains("lib-a"))
        assertTrue(capturedBody.contains("folder-a"))
        assertTrue(capturedBody.contains("name=\"files\""))
        assertTrue(capturedBody.contains("book.epub"))
        assertTrue(capturedBody.contains("epub-bytes"))
    }

    @Test fun `updateItemMedia patches ABS metadata`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.updateItemMedia(
            BASE_URL,
            "item-1",
            NetworkAbsMetadataUpdate(
                title = "A title",
                authors = listOf(NetworkAbsAuthorUpdate("An author")),
                series = listOf(NetworkAbsSeriesUpdate("A series", "2")),
                publishedYear = "1984",
                description = "A description",
            ),
            "T",
            false,
        )

        assertTrue(result is NetworkResult.Success)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "PATCH")
        assertEquals(req.url.encodedPath, "/api/items/item-1/media")
        assertEquals(req.headers["Authorization"], "Bearer T")
        assertTrue(capturedBody.contains("A title"))
        assertTrue(capturedBody.contains("An author"))
        assertTrue(capturedBody.contains("A series"))
        assertTrue(capturedBody.contains("1984"))
        assertTrue(capturedBody.contains("A description"))
    }

    @Test fun `updateItemChapters posts chapter markers`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.updateItemChapters(
            BASE_URL,
            "item-1",
            listOf(NetworkAbsChapterUpdate(0, 0.0, 12.5, "Chapter one")),
            "T",
            false,
        )

        assertTrue(result is NetworkResult.Success)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/items/item-1/chapters")
        assertTrue(capturedBody.contains("Chapter one"))
    }

    @Test fun `uploadItemCoverFromUrl posts cover source`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.uploadItemCoverFromUrl(
            BASE_URL,
            "item-1",
            "https://example.com/cover.jpg",
            "T",
            false,
        )

        assertTrue(result is NetworkResult.Success)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/items/item-1/cover")
        assertTrue(capturedBody.contains("https://example.com/cover.jpg"))
    }

    // endregion

    // region getListeningStats

    @Test fun `getListeningStats parses totalTime`() = runTest {
        val (_, client) = mockAbsClient("""{"totalTime":3600.5,"today":600}""" to HttpStatusCode.OK)

        val result = client.getListeningStats(BASE_URL, "T", false)

        val stats = (result as NetworkResult.Success).value
        assertEquals(3600.5, stats.totalTimeSec, 0.0)
    }

    @Test fun `getListeningStats hits me listening-stats endpoint`() = runTest {
        val (engine, client) = mockAbsClient("""{"totalTime":0}""" to HttpStatusCode.OK)

        client.getListeningStats(BASE_URL, "T", false)

        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/me/listening-stats")
    }

    @Test fun `getListeningStats 401 surfaces as Auth`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.Unauthorized)

        val result = client.getListeningStats(BASE_URL, "T", false)

        assertTrue(result is NetworkResult.Auth)
    }

    // endregion
}
