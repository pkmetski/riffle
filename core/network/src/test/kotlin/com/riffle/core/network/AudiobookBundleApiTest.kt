package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookBundleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AudiobookBundleApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AudiobookBundleApiImpl(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test fun freshDownload_sendsAcceptHeader_noRange_reportsTotalFromContentLength() = runTest {
        val bytes = ByteArray(64) { it.toByte() }
        server.enqueue(MockResponse().setHeader("Content-Length", "64").setBody(Buffer().write(bytes)))

        val result = api.openBundleStream(baseUrl(), "42", "tkn", insecureAllowed = false, fromByte = 0L)

        val recorded = server.takeRequest()
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertEquals("application/audiobook+zip", recorded.getHeader("Accept"))
        assertNull("No Range header on a fresh download", recorded.getHeader("Range"))
        assertTrue(result is NetworkAudiobookBundleResult.Success)
        result as NetworkAudiobookBundleResult.Success
        assertEquals(64L, result.totalBytes)
        assertFalse(result.isPartial)
        assertEquals(bytes.toList(), result.body.use { it.bytes() }.toList())
    }

    @Test fun resume_sendsRangeHeader_parsesTotalFromContentRange() = runTest {
        val tail = ByteArray(28) { (it + 100).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-127/128")
                .setBody(Buffer().write(tail)),
        )

        val result = api.openBundleStream(baseUrl(), "42", "tkn", insecureAllowed = false, fromByte = 100L)

        val recorded = server.takeRequest()
        assertEquals("bytes=100-", recorded.getHeader("Range"))
        assertTrue(result is NetworkAudiobookBundleResult.Success)
        result as NetworkAudiobookBundleResult.Success
        assertEquals(128L, result.totalBytes)
        assertTrue(result.isPartial)
    }

    @Test fun nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.openBundleStream(baseUrl(), "42", "tkn", insecureAllowed = false, fromByte = 0L)

        assertTrue(result is NetworkAudiobookBundleResult.NetworkError)
    }
}
