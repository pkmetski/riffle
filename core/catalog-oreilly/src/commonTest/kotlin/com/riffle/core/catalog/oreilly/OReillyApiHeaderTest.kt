package com.riffle.core.catalog.oreilly

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test

class OReillyApiHeaderTest {

    private fun makeClient(onRequest: (io.ktor.client.request.HttpRequestData) -> Unit): HttpClient {
        return HttpClient(MockEngine { request ->
            onRequest(request)
            respond("ok", HttpStatusCode.OK, headersOf())
        })
    }

    @Test
    fun `custom UA overrides the hardcoded default`() = runTest {
        val customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        var capturedUa: String? = null
        val client = makeClient { capturedUa = it.headers["User-Agent"] }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x", userAgent = customUa)
        api.getJson(api.browseUrl("books", 1, 5))
        assertEquals(customUa, capturedUa)
    }

    @Test
    fun `default UA is used when no custom UA provided`() = runTest {
        var capturedUa: String? = null
        val client = makeClient { capturedUa = it.headers["User-Agent"] }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        api.getJson(api.browseUrl("books", 1, 5))
        assertEquals(OReillyApi.DEFAULT_USER_AGENT, capturedUa)
    }

    @Test
    fun `JSON requests carry Origin Accept-Language and sec-fetch headers`() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        api.getJson(api.browseUrl("books", 1, 5))
        assertEquals(captured["Origin"], "https://learning.oreilly.com")
        assertEquals(captured["Accept-Language"], "en-US,en;q=0.9")
        assertEquals(captured["sec-fetch-dest"], "empty")
        assertEquals(captured["sec-fetch-mode"], "cors")
        assertEquals(captured["sec-fetch-site"], "same-origin")
    }

    @Test
    fun `content requests carry navigate sec-fetch headers`() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        api.getContent(api.fileContentUrl("123456789", "xhtml/ch01.html"))
        assertEquals(captured["sec-fetch-dest"], "document")
        assertEquals(captured["sec-fetch-mode"], "navigate")
        assertEquals(captured["sec-fetch-site"], "same-origin")
    }

    @Test
    fun `binary asset requests carry no-cors sec-fetch headers`() = runTest {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        api.getBytes(api.fileContentUrl("123456789", "images/cover.jpg"))
        assertEquals(captured["sec-fetch-dest"], "image")
        assertEquals(captured["sec-fetch-mode"], "no-cors")
        assertEquals(captured["sec-fetch-site"], "same-origin")
    }

    @Test
    fun `sec-ch-ua is derived from Chrome version in UA string`() {
        val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        val hint = OReillyApi.chromeClientHint(ua)
        assertEquals(hint, """"Chromium";v="128", "Google Chrome";v="128", "Not.A/Brand";v="24"""")
    }

    @Test
    fun `sec-ch-ua is empty when UA has no Chrome version`() {
        val hint = OReillyApi.chromeClientHint("Mozilla/5.0 (compatible; bot)")
        assertEquals(hint, "")
    }
}
