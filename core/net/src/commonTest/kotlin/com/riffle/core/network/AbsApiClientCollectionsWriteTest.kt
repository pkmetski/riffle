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

class AbsApiClientCollectionsWriteTest {

    @Test
    fun `createCollection posts libraryId name and book and returns parsed collection`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":null,"coverPath":null}}]}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.createCollection(BASE_URL, "lib-1", "To Read", "item-1", "tok", false)

        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/collections")
        assertEquals(req.headers["Authorization"], "Bearer tok")
        assertTrue(capturedBody.contains("\"libraryId\":\"lib-1\""))
        assertTrue(capturedBody.contains("\"name\":\"To Read\""))
        assertTrue(capturedBody.contains("\"books\":[\"item-1\"]"))

        assertTrue(result is NetworkResult.Success)
        val collection = (result as NetworkResult.Success).value
        assertNotNull(collection)
        assertEquals(collection!!.id, "col-1")
        assertEquals(collection.name, "To Read")
        assertEquals(1, collection.items.size)
    }

    @Test
    fun `createCollection without initial book sends empty books array`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[]}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        val result = client.createCollection(BASE_URL, "lib-1", "To Read", null, "tok", false)
        assertTrue(capturedBody.contains("\"books\":[]"))
        assertTrue(result is NetworkResult.Success)
        val collection = (result as NetworkResult.Success).value
        assertNotNull(collection)
        assertTrue(collection!!.items.isEmpty())
    }

    @Test
    fun `createCollection returns NetworkError on non-2xx`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.InternalServerError)
        val result = client.createCollection(BASE_URL, "lib-1", "To Read", null, "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `addBookToCollection posts libraryItemId to collection book endpoint`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":null,"coverPath":null}}]}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        val result = client.addBookToCollection(BASE_URL, "col-1", "item-1", "tok", false)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/collections/col-1/book")
        assertEquals(req.headers["Authorization"], "Bearer tok")
        assertTrue(capturedBody.contains("\"id\":\"item-1\""))
        assertTrue(result is NetworkResult.Success)
        val collection = (result as NetworkResult.Success).value
        assertNotNull(collection)
        assertEquals(collection!!.id, "col-1")
    }

    @Test
    fun `addBookToCollection returns NetworkError on 404`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.NotFound)
        val result = client.addBookToCollection(BASE_URL, "col-1", "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `removeBookFromCollection deletes the libraryItem from the collection`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[]}""" to HttpStatusCode.OK,
        )
        val result = client.removeBookFromCollection(BASE_URL, "col-1", "item-1", "tok", false)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "DELETE")
        assertEquals(req.url.encodedPath, "/api/collections/col-1/book/item-1")
        assertEquals(req.headers["Authorization"], "Bearer tok")
        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `removeBookFromCollection tolerates empty body on success`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.OK)
        val result = client.removeBookFromCollection(BASE_URL, "col-1", "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        assertEquals(null, (result as NetworkResult.Success).value)
    }

    @Test
    fun `removeBookFromCollection returns NetworkError on 404`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.NotFound)
        val result = client.removeBookFromCollection(BASE_URL, "col-1", "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }
}
