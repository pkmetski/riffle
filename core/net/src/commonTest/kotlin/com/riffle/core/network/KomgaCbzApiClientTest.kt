package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KomgaCbzApiClientTest {

    @Test
    fun `fetchCbzPageCount calls correct URL and parses pagesCount`() = runTest {
        val (engine, client) = mockKomgaLibraryClient(
            """{"id":"book1","media":{"pagesCount":42,"status":"READY"}}""" to HttpStatusCode.OK,
        )
        val count = client.fetchCbzPageCount(
            baseUrl = BASE_URL,
            bookId = "book1",
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        assertEquals(42, count)
        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/v1/books/book1")
    }

    @Test
    fun `fetchCbzPage uses 1-based page number in URL`() = runTest {
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val engine = MockEngine { _ ->
            respond(ByteReadChannel(imageBytes), HttpStatusCode.OK)
        }
        val client = KomgaLibraryApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.fetchCbzPage(
            baseUrl = BASE_URL,
            bookId = "book2",
            pageIndex = 0,
            maxWidth = null,
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/v1/books/book2/pages/1")
    }

    @Test
    fun `fetchCbzPage appends width param when maxWidth is set`() = runTest {
        val engine = MockEngine { _ ->
            respond(ByteReadChannel(byteArrayOf(1, 2, 3)), HttpStatusCode.OK)
        }
        val client = KomgaLibraryApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.fetchCbzPage(
            baseUrl = BASE_URL,
            bookId = "book3",
            pageIndex = 4,
            maxWidth = 300,
            token = "Basic dGVzdA==",
            insecureAllowed = false,
        )
        val req = engine.requestHistory[0]
        assertEquals(req.url.encodedPath, "/api/v1/books/book3/pages/5")
        assertTrue(req.url.encodedPathAndQuery.contains("width=300"))
    }
}
