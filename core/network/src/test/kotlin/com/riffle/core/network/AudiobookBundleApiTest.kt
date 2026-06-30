package com.riffle.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertEquals(64L, result.value.totalBytes)
        assertFalse(result.value.isPartial)
        assertEquals(bytes.toList(), result.value.body.use { it.bytes() }.toList())
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
        assertTrue(result is NetworkResult.Success)
        result as NetworkResult.Success
        assertEquals(128L, result.value.totalBytes)
        assertTrue(result.value.isPartial)
    }

    @Test fun cancelledDuringSlowHeaderWait_doesNotLeakConnection() = runBlocking {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val countingClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) { acquired.incrementAndGet() }
                override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
            })
            .build()
        val leakApi: AudiobookBundleApi = AudiobookBundleApiImpl(countingClient)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", "64")
                .setBody(Buffer().write(ByteArray(64)))
                .setHeadersDelay(500, TimeUnit.MILLISECONDS),
        )

        val job = launch(Dispatchers.IO) {
            leakApi.openBundleStream(baseUrl(), "42", "tkn", insecureAllowed = false, fromByte = 0L)
        }
        delay(100)
        job.cancel()
        job.join()

        var waited = 0
        while (acquired.get() == 0 || released.get() < acquired.get()) {
            if (waited >= 2000) break
            delay(50); waited += 50
        }

        assertTrue("Expected a connection to be acquired", acquired.get() >= 1)
        assertEquals("Leaked connection: acquired but never released", acquired.get(), released.get())
    }

    @Test fun nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.openBundleStream(baseUrl(), "42", "tkn", insecureAllowed = false, fromByte = 0L)

        assertTrue(result is NetworkResult.Offline)
    }
}
