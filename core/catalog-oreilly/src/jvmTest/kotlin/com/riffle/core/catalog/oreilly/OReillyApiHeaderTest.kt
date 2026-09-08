package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OReillyApiHeaderTest {

    private fun makeClient(onRequest: (io.ktor.client.request.HttpRequestData) -> Unit): HttpClient {
        return HttpClient(MockEngine { request ->
            onRequest(request)
            respond("ok", HttpStatusCode.OK, headersOf())
        })
    }

    @Test
    fun `custom UA overrides the hardcoded default`() {
        val customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        var capturedUa: String? = null
        val client = makeClient { capturedUa = it.headers["User-Agent"] }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x", userAgent = customUa)
        runBlocking { api.getJson(api.browseUrl("books", 1, 5)) }
        assertEquals(customUa, capturedUa)
    }

    @Test
    fun `default UA is used when no custom UA provided`() {
        var capturedUa: String? = null
        val client = makeClient { capturedUa = it.headers["User-Agent"] }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        runBlocking { api.getJson(api.browseUrl("books", 1, 5)) }
        assertEquals(OReillyApi.DEFAULT_USER_AGENT, capturedUa)
    }
}
