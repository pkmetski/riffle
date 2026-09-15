package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbsBookmarkApiTest {

    @Test
    fun `createBookmark posts time and title and parses returned bookmark`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"libraryItemId":"ITEM","time":123,"title":"x","createdAt":111}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.createBookmark(BASE_URL, "ITEM", 123, "x", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/me/item/ITEM/bookmark")
        assertTrue(capturedBody.contains("\"time\":123"))
        assertTrue(capturedBody.contains("\"title\":\"x\""))

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals(bookmark.libraryItemId, "ITEM")
        assertEquals(123, bookmark.timeSec)
        assertEquals(bookmark.title, "x")
        assertEquals(111L, bookmark.createdAt)
    }

    @Test
    fun `updateBookmark patches to same path and parses returned bookmark`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"libraryItemId":"ITEM","time":123,"title":"x","createdAt":111}""" to HttpStatusCode.OK,
        )
        val result = client.updateBookmark(BASE_URL, "ITEM", 123, "x", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "PATCH")
        assertEquals(req.url.encodedPath, "/api/me/item/ITEM/bookmark")

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals(123, bookmark.timeSec)
        assertEquals(bookmark.title, "x")
    }

    @Test
    fun `deleteBookmark deletes by time and synthesizes success from plain-text OK`() = runTest {
        val engine = MockEngine { _ ->
            respond("OK", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        val result = client.deleteBookmark(BASE_URL, "ITEM", 123, "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "DELETE")
        assertEquals(req.url.encodedPath, "/api/me/item/ITEM/bookmark/123")

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals(bookmark.libraryItemId, "ITEM")
        assertEquals(123, bookmark.timeSec)
    }

    @Test
    fun `deleteBookmark404IsTreatedAsSuccess`() = runTest {
        val (engine, client) = mockAbsClient("Not found" to HttpStatusCode.NotFound)
        val result = client.deleteBookmark(BASE_URL, "ITEM", 123, "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "DELETE")
        assertEquals(req.url.encodedPath, "/api/me/item/ITEM/bookmark/123")

        assertTrue(result is NetworkResult.Success)
        val bookmark = (result as NetworkResult.Success).value
        assertEquals(bookmark.libraryItemId, "ITEM")
        assertEquals(123, bookmark.timeSec)
    }

    @Test
    fun `listBookmarks parses bookmarks array from api me`() = runTest {
        val (engine, client) = mockAbsClient(
            ("""{"bookmarks":[{"libraryItemId":"ITEM","time":5,"title":"a","createdAt":1},""" +
                """{"libraryItemId":"ITEM","time":9,"title":"b","createdAt":2}],"id":"u","username":"x"}""") to HttpStatusCode.OK,
        )
        val result = client.listBookmarks(BASE_URL, "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "GET")
        assertEquals(req.url.encodedPath, "/api/me")

        assertTrue(result is NetworkResult.Success)
        val bookmarks = (result as NetworkResult.Success).value
        assertEquals(2, bookmarks.size)
        assertEquals(5, bookmarks[0].timeSec)
        assertEquals(bookmarks[0].title, "a")
        assertEquals(1L, bookmarks[0].createdAt)
        assertEquals(9, bookmarks[1].timeSec)
        assertEquals(bookmarks[1].title, "b")
    }

    @Test
    fun `listBookmarks returns empty list when bookmarks key absent`() = runTest {
        val (_, client) = mockAbsClient("""{"id":"u","username":"x"}""" to HttpStatusCode.OK)
        val result = client.listBookmarks(BASE_URL, "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).value.isEmpty())
    }

    @Test
    fun `createBookmark returns NetworkError on non-2xx`() = runTest {
        val (_, client) = mockAbsClient("error" to HttpStatusCode.InternalServerError)
        val result = client.createBookmark(BASE_URL, "ITEM", 123, "x", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }
}
