package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StorytellerApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StorytellerApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StorytellerApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun `login posts multipart form to api token and returns access_token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"tok-abc","token_type":"bearer","expires_in":12345}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = client.login(baseUrl(), "plamen", "secret", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals("tok-abc", (result as NetworkResult.Success).value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/token", request.path)
        val body = request.body.readUtf8()
        assertTrue("body should carry multipart username field, was: $body", body.contains("name=\"username\""))
        assertTrue("body should carry the username value, was: $body", body.contains("plamen"))
        assertTrue("body should carry the password value, was: $body", body.contains("secret"))
        assertTrue("body should be multipart, was: $body", body.contains("multipart") || request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `login 400 returns WrongCredentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Incorrect username or password"}"""))
        val result = client.login(baseUrl(), "plamen", "wrong", false)
        assertTrue(result is NetworkResult.Auth)
    }

    @Test
    fun `login unreachable host returns NetworkError`() = runTest {
        server.shutdown()
        val result = client.login("http://127.0.0.1:1", "plamen", "pass", false)
        assertTrue(result is NetworkResult.Offline)
    }

    @Test
    fun `validateToken 200 returns Valid and sends Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("true"))

        val result = client.validateToken(baseUrl(), "tok-xyz", false)

        assertTrue(result is NetworkResult.Success && result.value == true)
        val request = server.takeRequest()
        assertEquals("/api/validate", request.path)
        assertEquals("Bearer tok-xyz", request.getHeader("Authorization"))
    }

    @Test
    fun `validateToken 401 returns Invalid`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.validateToken(baseUrl(), "stale", false)
        assertTrue(result is NetworkResult.Success && result.value == false)
    }

    @Test
    fun `listReadalouds calls api books synced true and maps books`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """[
                        {"id":1385738337074647,"title":"The Martian: A Novel","authors":[{"name":"Andy Weir","file_as":"Andy Weir","role":"aut"}],"processing_status":null},
                        {"id":2941916867078952,"title":"The Early Asimov - 2","authors":[{"name":"Isaac Asimov","file_as":"Isaac Asimov","role":"aut"}],"processing_status":null}
                    ]""".trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val result = client.listReadalouds(baseUrl(), "tok", false)

        assertTrue(result is NetworkResult.Success)
        val books = (result as NetworkResult.Success).value
        assertEquals(2, books.size)
        assertEquals(1385738337074647L, books[0].id)
        assertEquals("The Martian: A Novel", books[0].title)
        assertEquals(listOf("Andy Weir"), books[0].authors)
        val request = server.takeRequest()
        assertEquals("/api/books?synced=true", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun `listReadalouds non-2xx returns NetworkError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client.listReadalouds(baseUrl(), "tok", false)
        assertTrue(result is NetworkResult.ServerError)
    }

    @Test
    fun `getBook returns single book on success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":42,"title":"Dune","authors":[{"name":"Herbert"}],"processing_status":null}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = client.getBook(baseUrl(), 42L, "tok", false)

        assertTrue(result is NetworkResult.Success)
        val book = (result as NetworkResult.Success).value
        assertEquals(42L, book.id)
        assertEquals("Dune", book.title)
        assertEquals(listOf("Herbert"), book.authors)
        val request = server.takeRequest()
        assertEquals("/api/books/42", request.path)
    }

    @Test
    fun `getBook 404 returns NotFound with the requested id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client.getBook(baseUrl(), 99L, "tok", false)
        assertTrue(result is NetworkResult.ServerError && result.code == 404)
    }

    @Test
    fun `coverUrl builds canonical Storyteller cover endpoint`() {
        val url = client.coverUrl("http://media-server:8001", 42L)
        assertEquals("http://media-server:8001/api/books/42/cover", url)
    }
}
