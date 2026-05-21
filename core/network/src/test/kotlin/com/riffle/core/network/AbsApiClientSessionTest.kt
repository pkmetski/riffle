package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientSessionTest {

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

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `syncEbookProgress sends PATCH to correct path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/item-1", req.path)
    }

    @Test
    fun `syncEbookProgress sends ebookLocation and ebookProgress in body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("epubcfi(/6/4!/4/1:0)", 0.25f),
            "tok", false,
        )
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(body.contains("\"ebookProgress\":0.25"))
    }

    @Test
    fun `syncEbookProgress sends Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "my-token", false,
        )
        val req = server.takeRequest()
        assertEquals("Bearer my-token", req.getHeader("Authorization"))
    }

    @Test
    fun `syncEbookProgress returns Success on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkSyncSessionResult.Success)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val result = client.syncEbookProgress(
            baseUrl(), "item-1",
            NetworkEbookProgressPayload("cfi", 0.5f),
            "tok", false,
        )
        assertTrue(result is NetworkSyncSessionResult.NetworkError)
    }

    @Test
    fun `syncEbookProgress returns NetworkError on unreachable host`() = runTest {
        server.shutdown()
        val result = client.syncEbookProgress(
            "http://127.0.0.1:1", "item-1",
            NetworkEbookProgressPayload("cfi", 0f),
            "tok", false,
        )
        assertTrue(result is NetworkSyncSessionResult.NetworkError)
    }
}
