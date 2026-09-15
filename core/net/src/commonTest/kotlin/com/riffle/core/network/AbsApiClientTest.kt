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

class AbsApiClientTest {

    @Test
    fun `login success returns user id and token`() = runTest {
        val (_, client) = mockAbsClient(
            """{"user":{"id":"user-123","username":"admin","token":"tok-abc"}}""" to HttpStatusCode.OK,
        )
        val result = client.login(BASE_URL, "admin", "secret", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(success.value.userId, "user-123")
        assertEquals(success.value.token, "tok-abc")
    }

    @Test
    fun `login request sends username and password in body`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("""{"user":{"id":"u","username":"admin","token":"t"}}""", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.login(BASE_URL, "admin", "s3cret", false)
        assertTrue(capturedBody.contains("\"username\":\"admin\""))
        assertTrue(capturedBody.contains("\"password\":\"s3cret\""))
        assertEquals(engine.requestHistory[0].url.encodedPath, "/login")
    }

    @Test
    fun `login 401 returns WrongCredentials`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.Unauthorized)
        val result = client.login(BASE_URL, "admin", "wrong", false)
        assertTrue(result is NetworkResult.Auth)
    }

    @Test
    fun `login unreachable host returns NetworkError`() = runTest {
        val client = offlineAbsClient()
        val result = client.login("http://127.0.0.1:1", "admin", "pass", false)
        assertTrue(result is NetworkResult.Offline)
    }

    // ABS exposes its version on the unauthenticated /status endpoint as `serverVersion`.
    // The /api/server-info path used previously does not exist (returns 404 even with a valid token).
    @Test
    fun `getServerInfo hits status and parses serverVersion`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"app":"audiobookshelf","serverVersion":"2.35.1","isInit":true,"language":"en-us"}""" to HttpStatusCode.OK,
        )
        val version = client.getServerInfo(BASE_URL, "tok", false)
        assertEquals(version, "2.35.1")
        assertEquals(engine.requestHistory[0].url.encodedPath, "/status")
    }

    @Test
    fun `getServerInfo returns null on 404`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.NotFound)
        val version = client.getServerInfo(BASE_URL, "tok", false)
        assertEquals(null, version)
    }
}
