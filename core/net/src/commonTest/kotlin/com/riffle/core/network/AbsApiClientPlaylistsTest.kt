package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsApiClientPlaylistsTest {

    @Test
    fun `getPlaylists issues GET with bearer token and parses response`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"results":[{
                "id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                  {"libraryItemId":"item-1","libraryItem":{
                    "id":"item-1","libraryId":"lib-1",
                    "media":{"metadata":{"title":"Book","authorName":"A"},"ebookFormat":"epub"}
                  }}
                ]
            }]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getPlaylists(BASE_URL, "lib-1", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "GET")
        assertEquals(req.url.encodedPath, "/api/libraries/lib-1/playlists")
        assertTrue(req.url.encodedPathAndQuery.contains("limit=500"))
        assertEquals(req.headers["Authorization"], "Bearer tok")

        assertTrue(result is NetworkResult.Success)
        val playlists = (result as NetworkResult.Success).value
        assertEquals(1, playlists.size)
        assertEquals(playlists[0].id, "pl-1")
        assertEquals(playlists[0].name, "To Read")
        assertEquals(1, playlists[0].items.size)
        assertEquals(playlists[0].items[0].id, "item-1")
    }

    @Test
    fun `getPlaylists includes bookIds even when libraryItem expansion is null`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{
                "id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                  {"libraryItemId":"item-1","libraryItem":{
                    "id":"item-1","libraryId":"lib-1",
                    "media":{"metadata":{"title":"Book","authorName":"A"}}
                  }},
                  {"libraryItemId":"item-2","libraryItem":null}
                ]
            }]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getPlaylists(BASE_URL, "lib-1", "tok", false)

        assertTrue(result is NetworkResult.Success)
        val pl = (result as NetworkResult.Success).value.single()
        assertEquals(setOf("item-1", "item-2"), pl.bookIds)
        assertEquals(listOf("item-1"), pl.items.map { it.id })
        assertEquals(2, pl.bookCount)
    }

    @Test
    fun `createPlaylist posts libraryId name and initial item`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                    {"libraryItemId":"item-1","libraryItem":{
                      "id":"item-1","libraryId":"lib-1",
                      "media":{"metadata":{"title":"T","authorName":"A"},"ebookFormat":"epub"}
                    }}
                ]}""".trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.createPlaylist(BASE_URL, "lib-1", "To Read", "item-1", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/playlists")
        assertEquals(req.headers["Authorization"], "Bearer tok")
        assertEquals(
            """{"libraryId":"lib-1","name":"To Read","items":[{"libraryItemId":"item-1"}]}""",
            capturedBody,
        )

        assertTrue(result is NetworkResult.Success)
        val playlist = (result as NetworkResult.Success).value
        assertNotNull(playlist)
        assertEquals(playlist!!.id, "pl-1")
        assertEquals(1, playlist.items.size)
    }

    @Test
    fun `createPlaylist without initial book sends empty items array`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[]}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.createPlaylist(BASE_URL, "lib-1", "To Read", null, "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/playlists")
        assertEquals(
            """{"libraryId":"lib-1","name":"To Read","items":[]}""",
            capturedBody,
        )
        assertTrue(result is NetworkResult.Success)
        val playlist = (result as NetworkResult.Success).value
        assertNotNull(playlist)
        assertTrue(playlist!!.items.isEmpty())
    }

    @Test
    fun `addBookToPlaylist posts libraryItemId to singular item endpoint`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                    {"libraryItemId":"item-1","libraryItem":{
                      "id":"item-1","libraryId":"lib-1",
                      "media":{"metadata":{"title":"T","authorName":"A"},"ebookFormat":"epub"}
                    }}
                ]}""".trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.addBookToPlaylist(BASE_URL, "pl-1", "item-1", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/playlists/pl-1/item")
        assertEquals(req.headers["Authorization"], "Bearer tok")
        assertEquals(capturedBody, """{"libraryItemId":"item-1"}""")

        assertTrue(result is NetworkResult.Success)
        val playlist = (result as NetworkResult.Success).value
        assertNotNull(playlist)
        assertEquals(playlist!!.id, "pl-1")
    }

    @Test
    fun `removeBookFromPlaylist deletes singular item path`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[]}""" to HttpStatusCode.OK,
        )

        val result = client.removeBookFromPlaylist(BASE_URL, "pl-1", "item-1", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "DELETE")
        assertEquals(req.url.encodedPath, "/api/playlists/pl-1/item/item-1")
        assertEquals(req.headers["Authorization"], "Bearer tok")

        assertTrue(result is NetworkResult.Success)
    }
}
