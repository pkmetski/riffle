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

    @Test
    fun `JSON requests carry Origin, Accept-Language, and sec-fetch headers`() {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        runBlocking { api.getJson(api.browseUrl("books", 1, 5)) }
        assertEquals("https://learning.oreilly.com", captured["Origin"])
        assertEquals("en-US,en;q=0.9", captured["Accept-Language"])
        assertEquals("empty", captured["sec-fetch-dest"])
        assertEquals("cors", captured["sec-fetch-mode"])
        assertEquals("same-origin", captured["sec-fetch-site"])
    }

    @Test
    fun `content requests carry navigate sec-fetch headers`() {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        runBlocking { api.getContent(api.fileContentUrl("123456789", "xhtml/ch01.html")) }
        assertEquals("document", captured["sec-fetch-dest"])
        assertEquals("navigate", captured["sec-fetch-mode"])
        assertEquals("same-origin", captured["sec-fetch-site"])
    }

    @Test
    fun `binary asset requests carry no-cors sec-fetch headers`() {
        val captured = mutableMapOf<String, String>()
        val client = makeClient { req -> req.headers.forEach { key, vals -> captured[key] = vals.first() } }
        val api = OReillyApi(client = client, cookieHeader = "orm-jwt=x")
        runBlocking { api.getBytes(api.fileContentUrl("123456789", "images/cover.jpg")) }
        assertEquals("image", captured["sec-fetch-dest"])
        assertEquals("no-cors", captured["sec-fetch-mode"])
        assertEquals("same-origin", captured["sec-fetch-site"])
    }

    @Test
    fun `sec-ch-ua is derived from Chrome version in UA string`() {
        val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        val hint = OReillyApi.chromeClientHint(ua)
        assertEquals(""""Chromium";v="128", "Google Chrome";v="128", "Not.A/Brand";v="24"""", hint)
    }

    @Test
    fun `sec-ch-ua is empty when UA has no Chrome version`() {
        val hint = OReillyApi.chromeClientHint("Mozilla/5.0 (compatible; bot)")
        assertEquals("", hint)
    }
}
