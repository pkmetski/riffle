package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OReillyPlaylistTest {

    private val listJson = """
        [
          {"id":"pl-1","name":"To Read","description":"","content_count":2,
           "content":[
             {"id":"ci-1","ourn":"urn:orm:book:9781492034025","content_type":"book"},
             {"id":"ci-2","ourn":"urn:orm:book:9781491954249","content_type":"book"}
           ]},
          {"id":"pl-2","name":"Favorites","description":"","content_count":0,"content":[]}
        ]
    """.trimIndent()

    private val createJson = """
        {"id":"pl-new","name":"To Read","description":"","content_count":0,"content":[]}
    """.trimIndent()

    private val addContentJson = """[{"id":"ci-3","content_type":"book"}]"""

    data class Recorded(val url: String, val method: HttpMethod, val body: String?)

    private fun makeApi(
        responses: List<String>,
        onRequest: ((Recorded) -> Unit)? = null,
    ): OReillyApi {
        var callIndex = 0
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString().takeIf { it.isNotEmpty() }
            onRequest?.invoke(Recorded(request.url.toString(), request.method, body))
            val resp = responses.getOrElse(callIndex) { "{}" }
            callIndex++
            respond(resp, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        return OReillyApi(client = HttpClient(engine), cookieHeader = "orm-jwt=test")
    }

    @Test
    fun listPlaylists_returnsCollectionsFromApi() = runTest {
        val api = makeApi(listOf(listJson))
        val catalog = fakeCatalog(api)
        val playlists = catalog.listPlaylists("books")
        assertEquals(2, playlists.size)
        assertEquals("pl-1", playlists[0].id)
        assertEquals("To Read", playlists[0].name)
        assertEquals(2, playlists[0].bookCount)
        assertEquals(
            listOf("urn:orm:book:9781492034025", "urn:orm:book:9781491954249"),
            playlists[0].itemIds,
        )
        assertEquals("pl-2", playlists[1].id)
        assertEquals(0, playlists[1].bookCount)
        assertTrue(playlists[1].itemIds.isEmpty())
    }

    @Test
    fun findPlaylist_byName_found() = runTest {
        val api = makeApi(listOf(listJson))
        val catalog = fakeCatalog(api)
        val playlist = catalog.findPlaylist("books", "To Read")
        assertEquals("pl-1", playlist?.id)
        assertEquals("To Read", playlist?.name)
    }

    @Test
    fun findPlaylist_byName_notFound() = runTest {
        val api = makeApi(listOf(listJson))
        val catalog = fakeCatalog(api)
        assertNull(catalog.findPlaylist("books", "Does Not Exist"))
    }

    @Test
    fun createPlaylist_withoutInitialItem_postsNameAndReturnsId() = runTest {
        val recorded = mutableListOf<Recorded>()
        val api = makeApi(listOf(createJson)) { recorded.add(it) }
        val catalog = fakeCatalog(api)
        val playlist = catalog.createPlaylist("books", "To Read", initialItemId = null)
        assertEquals(1, recorded.size)
        assertEquals("pl-new", playlist.id)
        assertEquals("To Read", playlist.name)
        val body = Json.parseToJsonElement(recorded[0].body!!)
        assertEquals("To Read", body.jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun createPlaylist_withInitialItem_alsoPosts_addContent() = runTest {
        val recorded = mutableListOf<Recorded>()
        val api = makeApi(listOf(createJson, addContentJson)) { recorded.add(it) }
        val catalog = fakeCatalog(api)
        catalog.createPlaylist("books", "To Read", initialItemId = "9781492034025")
        assertEquals(2, recorded.size, "Expected create + add-content calls")
        assertTrue(recorded[1].url.contains("add-content-ourns"))
        val addBody = Json.parseToJsonElement(recorded[1].body!!)
        val content = addBody.jsonObject["content"]?.jsonArray
        assertEquals("urn:orm:book:9781492034025", content?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun addItemToPlaylist_postsOurnToCorrectUrl() = runTest {
        val recorded = mutableListOf<Recorded>()
        val api = makeApi(listOf(addContentJson)) { recorded.add(it) }
        val catalog = fakeCatalog(api)
        catalog.addItemToPlaylist("pl-1", "9781492034025")
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].url.contains("/api/v3/collections/pl-1/add-content-ourns/"))
        val body = Json.parseToJsonElement(recorded[0].body!!)
        val content = body.jsonObject["content"]?.jsonArray
        assertEquals("urn:orm:book:9781492034025", content?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun removeItemFromPlaylist_postsOurnToCorrectUrl() = runTest {
        val recorded = mutableListOf<Recorded>()
        val api = makeApi(listOf("{}")) { recorded.add(it) }
        val catalog = fakeCatalog(api)
        catalog.removeItemFromPlaylist("pl-1", "9781492034025")
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].url.contains("/api/v3/collections/pl-1/remove-content-ourns/"))
        val body = Json.parseToJsonElement(recorded[0].body!!)
        val content = body.jsonObject["content"]?.jsonArray
        assertEquals("urn:orm:book:9781492034025", content?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun postJson_carries_versionV3Header() = runTest {
        var capturedVersion: String? = null
        val engine = MockEngine { request ->
            capturedVersion = request.headers["version"]
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        val api = OReillyApi(client = HttpClient(engine), cookieHeader = "orm-jwt=test")
        api.postJson(api.collectionsUrl(), "{}")
        assertEquals("v3", capturedVersion)
    }

    private fun fakeCatalog(api: OReillyApi): OReillyCatalog = OReillyCatalog(
        api = api,
        bytesClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
        cookieHeader = "orm-jwt=test",
    )
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonArray
    get() = this as kotlinx.serialization.json.JsonArray
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
