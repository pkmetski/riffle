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

class AbsApiClientSessionTest {

    @Test
    fun `syncEbookProgress sends PATCH to correct path`() = runTest {
        val (engine, client) = mockAbsClient("{}" to HttpStatusCode.OK)
        client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "PATCH")
        assertEquals(req.url.encodedPath, "/api/me/progress/item-1")
    }

    @Test
    fun `syncEbookProgress sends ebookLocation and ebookProgress in body`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        assertTrue(capturedBody.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(capturedBody.contains("\"ebookProgress\":0.25"))
    }

    // A plain reader position save (isFinished = null) must NOT carry isFinished, or every save
    // would risk flipping the item's finished/audio state on the server.
    @Test
    fun `syncEbookProgress omits isFinished from body when null`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.25f, isFinished = null),
            "tok", false,
        )
        assertTrue(!capturedBody.contains("isFinished"))
    }

    // A mark-read/unread carries isFinished so ABS also resets the audio dimension in the same PATCH.
    @Test
    fun `syncEbookProgress includes isFinished in body when set`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK, jsonHeaders())
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("", 0.0f, isFinished = false),
            "tok", false,
        )
        assertTrue(capturedBody.contains("\"isFinished\":false"))
    }

    @Test
    fun `syncEbookProgress sends Authorization Bearer header`() = runTest {
        val (engine, client) = mockAbsClient("{}" to HttpStatusCode.OK)
        client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "my-token", false,
        )
        assertEquals(engine.requestHistory[0].headers["Authorization"], "Bearer my-token")
    }

    @Test
    fun `syncEbookProgress returns Success on 200`() = runTest {
        val (_, client) = mockAbsClient("{}" to HttpStatusCode.OK)
        val result = client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on non-2xx`() = runTest {
        val (_, client) = mockAbsClient("{}" to HttpStatusCode.InternalServerError)
        val result = client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on unreachable host`() = runTest {
        val client = offlineAbsClient()
        val result = client.syncEbookProgress(
            "http://127.0.0.1:1", "item-1",
            NetworkEbookProgressPayload("cfi", 0f),
            "tok", false,
        )
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate 0 when server responds with plain-text OK`() = runTest {
        val engine = MockEngine { _ ->
            respond("OK", HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "text/plain"))
        }
        val client = AbsApiClient(HttpClient(engine) { install(ContentNegotiation) { json(RIFFLE_JSON) } })
        val result = client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(0L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate when server responds with JSON containing lastUpdate`() = runTest {
        val (_, client) = mockAbsClient(
            """{"ebookLocation":"cfi","lastUpdate":1779445105751}""" to HttpStatusCode.OK,
        )
        val result = client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(1779445105751L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `syncEbookProgress returns Success with lastUpdate 0 when server responds with empty JSON`() = runTest {
        val (_, client) = mockAbsClient("{}" to HttpStatusCode.OK)
        val result = client.syncEbookProgress(
            BASE_URL, "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(0L, (result as NetworkResult.Success).value)
    }

    @Test
    fun `getProgress parses complex CFI with element ID and character offset`() = runTest {
        val (_, client) = mockAbsClient(
            """{"ebookLocation":"epubcfi(/6/160!/4/4[heading_id_2]/1:0)","lastUpdate":1779445105751}""" to HttpStatusCode.OK,
        )
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals(progress.ebookLocation, "epubcfi(/6/160!/4/4[heading_id_2]/1:0)")
        assertEquals(1779445105751L, progress.lastUpdate)
    }

    @Test
    fun `getProgress parses all standard ABS fields`() = runTest {
        val (_, client) = mockAbsClient(
            """
                {
                    "id":"prog-1",
                    "userId":"user-1",
                    "libraryItemId":"item-1",
                    "episodeId":null,
                    "mediaItemId":"item-1",
                    "mediaItemType":"book",
                    "duration":3600.0,
                    "progress":0.9897289586305278,
                    "currentTime":3560.0,
                    "isFinished":false,
                    "hideFromContinueListening":false,
                    "ebookLocation":"epubcfi(/6/160!/4/4[heading_id_2]/1:0)",
                    "ebookProgress":0.9897289586305278,
                    "lastUpdate":1779445105751,
                    "startedAt":1779000000000,
                    "finishedAt":null
                }
            """.trimIndent() to HttpStatusCode.OK,
        )
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals(progress.ebookLocation, "epubcfi(/6/160!/4/4[heading_id_2]/1:0)")
        assertEquals(1779445105751L, progress.lastUpdate)
    }

    @Test
    fun `getProgress 404 returns Success with lastUpdate zero and empty ebookLocation`() = runTest {
        val (_, client) = mockAbsClient("Not Found" to HttpStatusCode.NotFound)
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals(progress.ebookLocation, "")
        assertEquals(0f, progress.ebookProgress, 0.001f)
        assertEquals(0L, progress.lastUpdate)
    }

    @Test
    fun `getProgress non-404 non-2xx returns NetworkError`() = runTest {
        val (_, client) = mockAbsClient("Internal Server Error" to HttpStatusCode.InternalServerError)
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getProgress parses ebookProgress field from ABS response`() = runTest {
        val (_, client) = mockAbsClient(
            """{"ebookLocation":"epubcfi(/6/4!/4/2/1:5)","ebookProgress":0.42,"lastUpdate":9000}""" to HttpStatusCode.OK,
        )
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val progress = (result as NetworkResult.Success).value
        assertEquals(0.42f, progress.ebookProgress, 0.001f)
        assertEquals(9000L, progress.lastUpdate)
    }

    @Test
    fun `getProgress returns NetworkError on empty body`() = runTest {
        val (_, client) = mockAbsClient("" to HttpStatusCode.OK)
        val result = client.getProgress(BASE_URL, "item-1", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }
}
