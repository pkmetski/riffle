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

class AbsApiClientCollectionsWriteTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before fun setUp() { server = MockWebServer(); server.start(); client = AbsApiClient(OkHttpClient()) }
    @After fun tearDown() { server.shutdown() }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `createCollection posts libraryId name and book and returns parsed collection`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":null,"coverPath":null}}]}"""
                )
                .addHeader("Content-Type", "application/json")
        )

        val result = client.createCollection(baseUrl(), "lib-1", "To Read", "item-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/collections", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"libraryId\":\"lib-1\""))
        assertTrue(body.contains("\"name\":\"To Read\""))
        assertTrue(body.contains("\"books\":[\"item-1\"]"))

        assertTrue(result is NetworkCollectionWriteResult.Success)
        val collection = (result as NetworkCollectionWriteResult.Success).collection
        assertEquals("col-1", collection?.id)
        assertEquals("To Read", collection?.name)
        assertEquals(1, collection?.items?.size)
    }

    @Test
    fun `createCollection without initial book sends empty books array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"col-1","name":"To Read","libraryId":"lib-1","books":[]}"""
            ).addHeader("Content-Type", "application/json")
        )
        client.createCollection(baseUrl(), "lib-1", "To Read", null, "tok", false)
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"books\":[]"))
    }

    @Test
    fun `createCollection returns NetworkError on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client.createCollection(baseUrl(), "lib-1", "To Read", null, "tok", false)
        assertTrue(result is NetworkCollectionWriteResult.NetworkError)
    }
}
