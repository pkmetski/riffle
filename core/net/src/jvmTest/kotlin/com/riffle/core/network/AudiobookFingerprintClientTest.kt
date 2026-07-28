package com.riffle.core.network

import com.riffle.core.network.createDefaultHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookFingerprintClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `storyteller fetches v2 and returns the ingested-source fingerprint`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"audiobook":{"fileSize":313869927,"duration":39214.464,
                   "manifest":{"readingOrder":[{"duration":39214.464}]}}}""",
            ).addHeader("Content-Type", "application/json"),
        )
        val client = StorytellerApiClient(createDefaultHttpClient(OkHttpClient()))
        val result = client.getAudiobookFingerprint(baseUrl(), 42L, "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals(313_869_927L, (result as NetworkResult.Success).value!!.fileSizeBytes)
        assertEquals("/api/v2/books/42", server.takeRequest().path)
    }

    @Test
    fun `storyteller with no audiobook returns NoAudiobook`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"title":"x"}""").addHeader("Content-Type", "application/json"))
        val result = StorytellerApiClient(createDefaultHttpClient(OkHttpClient())).getAudiobookFingerprint(baseUrl(), 1L, "tok", false)
        assertTrue(result is NetworkResult.Success && result.value == null)
    }

    @Test
    fun `abs fetches the expanded item and returns its fingerprint`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"abc","media":{"duration":8356.0,"audioFiles":[
                   {"index":1,"duration":2204.0,"metadata":{"size":300}},
                   {"index":2,"duration":1721.0,"metadata":{"size":200}}]}}""",
            ).addHeader("Content-Type", "application/json"),
        )
        val result = AbsApiClient(createDefaultHttpClient(OkHttpClient())).getAudiobookFingerprint(baseUrl(), "abc", "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf(2204.0, 1721.0), (result as NetworkResult.Success).value!!.trackDurationsSec)
        assertEquals("/api/items/abc?expanded=1", server.takeRequest().path)
    }

    @Test
    fun `abs fetches streamable tracks with ino`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"abc","media":{"audioFiles":[
                   {"ino":"7963985","index":1,"duration":39214.464}]}}""",
            ).addHeader("Content-Type", "application/json"),
        )
        val result = AbsApiClient(createDefaultHttpClient(OkHttpClient())).getAudiobookTracks(baseUrl(), "abc", "tok", false)
        assertTrue(result is NetworkResult.Success)
        assertEquals("7963985", (result as NetworkResult.Success).value.single().ino)
    }
}
