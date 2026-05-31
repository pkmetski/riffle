package com.riffle.core.network

import com.riffle.core.domain.InsecureConnectionType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login success returns user id and token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"user":{"id":"user-123","username":"admin","token":"tok-abc"}}""")
                .addHeader("Content-Type", "application/json")
        )
        val result = client.login(server.url("/").toString().trimEnd('/'), "admin", "secret", false)
        assertTrue(result is NetworkLoginResult.Success)
        val success = result as NetworkLoginResult.Success
        assertEquals("user-123", success.userId)
        assertEquals("tok-abc", success.token)
    }

    @Test
    fun `login request sends username and password in body`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"user":{"id":"u","username":"admin","token":"t"}}""")
                .addHeader("Content-Type", "application/json")
        )
        client.login(server.url("/").toString().trimEnd('/'), "admin", "s3cret", false)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"username\":\"admin\""))
        assertTrue(body.contains("\"password\":\"s3cret\""))
        assertEquals("/login", request.path)
    }

    @Test
    fun `login 401 returns WrongCredentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.login(server.url("/").toString().trimEnd('/'), "admin", "wrong", false)
        assertTrue(result is NetworkLoginResult.WrongCredentials)
    }

    @Test
    fun `login unreachable host returns NetworkError`() = runTest {
        server.shutdown()
        val result = client.login("http://127.0.0.1:1", "admin", "pass", false)
        assertTrue(result is NetworkLoginResult.NetworkError)
    }

    // ABS exposes its version on the unauthenticated /status endpoint as `serverVersion`.
    // The /api/server-info path used previously does not exist (returns 404 even with a valid token).
    @Test
    fun `getServerInfo hits status and parses serverVersion`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"app":"audiobookshelf","serverVersion":"2.35.1","isInit":true,"language":"en-us"}""")
                .addHeader("Content-Type", "application/json")
        )
        val version = client.getServerInfo(server.url("/").toString().trimEnd('/'), "tok", false)
        assertEquals("2.35.1", version)
        val request = server.takeRequest()
        assertEquals("/status", request.path)
    }

    @Test
    fun `getServerInfo returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val version = client.getServerInfo(server.url("/").toString().trimEnd('/'), "tok", false)
        assertEquals(null, version)
    }
}
