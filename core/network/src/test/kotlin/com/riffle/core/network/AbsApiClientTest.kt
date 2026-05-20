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
        val result = client.login(server.url("/").toString().trimEnd('/'), "admin", "secret")
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
        client.login(server.url("/").toString().trimEnd('/'), "admin", "s3cret")
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"username\":\"admin\""))
        assertTrue(body.contains("\"password\":\"s3cret\""))
        assertEquals("/api/login", request.path)
    }

    @Test
    fun `login 401 returns WrongCredentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.login(server.url("/").toString().trimEnd('/'), "admin", "wrong")
        assertTrue(result is NetworkLoginResult.WrongCredentials)
    }

    @Test
    fun `login unreachable host returns NetworkError`() = runTest {
        server.shutdown()
        val result = client.login("http://127.0.0.1:1", "admin", "pass")
        assertTrue(result is NetworkLoginResult.NetworkError)
    }
}
