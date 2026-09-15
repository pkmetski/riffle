package com.riffle.core.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudiobookFingerprintClientTest {

    @Test
    fun `storyteller fetches v2 and returns the ingested-source fingerprint`() = runTest {
        val (engine, client) = mockStorytellerClient(
            """{"audiobook":{"fileSize":313869927,"duration":39214.464,
               "manifest":{"readingOrder":[{"duration":39214.464}]}}}""" to HttpStatusCode.OK,
        )
        val result = client.getAudiobookFingerprint(BASE_URL, 42L, "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals(313_869_927L, (result as NetworkResult.Success).value!!.fileSizeBytes)
        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/v2/books/42")
    }

    @Test
    fun `storyteller with no audiobook returns NoAudiobook`() = runTest {
        val (_, client) = mockStorytellerClient("""{"title":"x"}""" to HttpStatusCode.OK)
        val result = client.getAudiobookFingerprint(BASE_URL, 1L, "tok", false)
        assertTrue(result is NetworkResult.Success && result.value == null)
    }

    @Test
    fun `abs fetches the expanded item and returns its fingerprint`() = runTest {
        val (engine, client) = mockAbsClient(
            """{"id":"abc","media":{"duration":8356.0,"audioFiles":[
               {"index":1,"duration":2204.0,"metadata":{"size":300}},
               {"index":2,"duration":1721.0,"metadata":{"size":200}}]}}""" to HttpStatusCode.OK,
        )
        val result = client.getAudiobookFingerprint(BASE_URL, "abc", "tok", false)

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf(2204.0, 1721.0), (result as NetworkResult.Success).value!!.trackDurationsSec)
        assertEquals(engine.requestHistory[0].url.encodedPath, "/api/items/abc")
        assertTrue(engine.requestHistory[0].url.encodedPathAndQuery.contains("expanded=1"))
    }

    @Test
    fun `abs fetches streamable tracks with ino`() = runTest {
        val (_, client) = mockAbsClient(
            """{"id":"abc","media":{"audioFiles":[
               {"ino":"7963985","index":1,"duration":39214.464}]}}""" to HttpStatusCode.OK,
        )
        val result = client.getAudiobookTracks(BASE_URL, "abc", "tok", false)
        assertTrue(result is NetworkResult.Success)
        assertEquals((result as NetworkResult.Success).value.single().ino, "7963985")
    }
}
