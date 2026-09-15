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
import kotlin.test.assertTrue

class StorytellerApiClientTest {

    @Test
    fun `login posts multipart form to api token and returns access_token`() = runTest {
        var capturedBody = ""
        var capturedContentType = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            capturedContentType = request.headers["Content-Type"] ?: ""
            respond(
                """{"access_token":"tok-abc","token_type":"bearer","expires_in":12345}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val client = StorytellerApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })

        val result = client.login(BASE_URL, "plamen", "secret", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals((result as NetworkResult.Success).value, "tok-abc")
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/token")
        assertTrue(capturedBody.contains("name=username") || capturedBody.contains("name=\"username\""), "body should carry multipart username field, was: $capturedBody")
        assertTrue(capturedBody.contains("plamen"), "body should carry the username value, was: $capturedBody")
        assertTrue(capturedBody.contains("secret"), "body should carry the password value, was: $capturedBody")
        assertTrue(capturedBody.contains("Content-Disposition: form-data") || capturedContentType.startsWith("multipart/form-data"), "body should be multipart, was: $capturedBody")
    }

    @Test
    fun `login 400 returns WrongCredentials`() = runTest {
        val (_, client) = mockStorytellerClient("""{"message":"Incorrect username or password"}""" to HttpStatusCode.BadRequest)
        val result = client.login(BASE_URL, "plamen", "wrong", false)
        assertTrue(result is NetworkResult.Auth)
    }

    @Test
    fun `login unreachable host returns NetworkError`() = runTest {
        val engine = MockEngine { throw NetworkOfflineException("Simulated network unreachable") }
        val client = StorytellerApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        val result = client.login("http://127.0.0.1:1", "plamen", "pass", false)
        assertTrue(result is NetworkResult.Offline)
    }

    @Test
    fun `validateToken 200 returns Valid and sends Authorization Bearer header`() = runTest {
        val (engine, client) = mockStorytellerClient("true" to HttpStatusCode.OK)

        val result = client.validateToken(BASE_URL, "tok-xyz", false)

        assertTrue(result is NetworkResult.Success && result.value == true)
        val req = engine.requestHistory[0]
        assertEquals(req.url.encodedPath, "/api/validate")
        assertEquals(req.headers["Authorization"], "Bearer tok-xyz")
    }

    @Test
    fun `validateToken 401 returns Invalid`() = runTest {
        val (_, client) = mockStorytellerClient("" to HttpStatusCode.Unauthorized)
        val result = client.validateToken(BASE_URL, "stale", false)
        assertTrue(result is NetworkResult.Success && result.value == false)
    }

    @Test
    fun `listReadalouds calls api books synced true and maps books`() = runTest {
        val (engine, client) = mockStorytellerClient(
            """[
                {"id":1385738337074647,"title":"The Martian: A Novel","authors":[{"name":"Andy Weir","file_as":"Andy Weir","role":"aut"}],"processing_status":null},
                {"id":2941916867078952,"title":"The Early Asimov - 2","authors":[{"name":"Isaac Asimov","file_as":"Isaac Asimov","role":"aut"}],"processing_status":null}
            ]""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.listReadalouds(BASE_URL, "tok", false)

        assertTrue(result is NetworkResult.Success)
        val books = (result as NetworkResult.Success).value
        assertEquals(2, books.size)
        assertEquals(1385738337074647L, books[0].id)
        assertEquals(books[0].title, "The Martian: A Novel")
        assertEquals(listOf("Andy Weir"), books[0].authors)
        val req = engine.requestHistory[0]
        assertEquals(req.url.encodedPath, "/api/books")
        assertTrue(req.url.encodedPathAndQuery.contains("synced=true"))
        assertEquals(req.headers["Authorization"], "Bearer tok")
    }

    @Test
    fun `listReadalouds non-2xx returns NetworkError`() = runTest {
        val (_, client) = mockStorytellerClient("" to HttpStatusCode.InternalServerError)
        val result = client.listReadalouds(BASE_URL, "tok", false)
        assertTrue(result is NetworkResult.ServerError)
    }

    @Test
    fun `getBook returns single book on success`() = runTest {
        val (engine, client) = mockStorytellerClient(
            """{"id":42,"title":"Dune","authors":[{"name":"Herbert"}],"processing_status":null}""" to HttpStatusCode.OK,
        )

        val result = client.getBook(BASE_URL, 42L, "tok", false)

        assertTrue(result is NetworkResult.Success)
        val book = (result as NetworkResult.Success).value
        assertEquals(42L, book.id)
        assertEquals(book.title, "Dune")
        assertEquals(listOf("Herbert"), book.authors)
        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/books/42")
    }

    @Test
    fun `getBook 404 returns NotFound with the requested id`() = runTest {
        val (_, client) = mockStorytellerClient("" to HttpStatusCode.NotFound)
        val result = client.getBook(BASE_URL, 99L, "tok", false)
        assertTrue(result is NetworkResult.ServerError && result.code == 404)
    }

    @Test
    fun `coverUrl builds canonical Storyteller cover endpoint`() {
        val (_, client) = mockStorytellerClient()
        val url = client.coverUrl("http://media-server:8001", 42L)
        assertEquals(url, "http://media-server:8001/api/books/42/cover")
    }
}
