package com.riffle.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest

class StorytellerBundleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var impl: StorytellerBundleApiImpl
    private lateinit var api: StorytellerBundleApi
    private lateinit var probe: StorytellerBundleProbeApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        impl = StorytellerBundleApiImpl(OkHttpClient())
        api = impl
        probe = impl
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test fun downloadBundle_callsExpectedPath_withBearerAuth() = runTest {
        val bytes = ByteArray(64) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkResult.Success)
        val readBytes = (result as NetworkResult.Success).value.body.use { it.bytes() }
        assertEquals(bytes.toList(), readBytes.toList())
    }

    @Test fun downloadBundle_nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.downloadBundle(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkResult.Offline)
    }

    @Test fun downloadBundle_cancelledDuringSlowHeaderWait_doesNotLeakConnection() = runBlocking {
        // Storyteller takes 1.5–5s to answer /synced; if the reader navigates away in that window
        // the coroutine is cancelled and withContext discards the returned Success(body) — the open
        // ResponseBody (and its connection) leaks unless the API closes it on cancellation.
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val countingClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) { acquired.incrementAndGet() }
                override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
            })
            .build()
        val leakApi: StorytellerBundleApi = StorytellerBundleApiImpl(countingClient)

        // Headers arrive only after 500ms, so execute() is still blocked when we cancel at ~100ms.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(64)))
                .setHeadersDelay(500, TimeUnit.MILLISECONDS),
        )

        val job = launch(Dispatchers.IO) {
            // Caller never reaches its body.use{} — it is cancelled mid-request.
            leakApi.downloadBundle(
                baseUrl = server.url("/").toString().trimEnd('/'),
                bookId = "42",
                token = "tkn",
                insecureAllowed = false,
            )
        }
        delay(100)
        job.cancel()
        job.join() // returns once the blocking execute() unwinds and withContext observes the cancel

        // Allow the connection-release callback to settle after the body is closed.
        var waited = 0
        while (acquired.get() == 0 || released.get() < acquired.get()) {
            if (waited >= 2000) break
            delay(50); waited += 50
        }

        assertTrue("Expected a connection to be acquired", acquired.get() >= 1)
        assertEquals("Leaked connection: acquired but never released", acquired.get(), released.get())
    }

    @Test fun probeBundleSize_returnsContentLength_fromHeadResponse() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", "315074677"))

        val result = probe.probeBundleSize(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertEquals("/api/books/42/synced", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue("Expected Success but got $result", result is NetworkResult.Success)
        assertEquals(315_074_677L, (result as NetworkResult.Success).value)
    }

    @Test fun probeBundleSize_nonSuccess_returnsNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = probe.probeBundleSize(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )

        assertTrue(result is NetworkResult.Offline)
    }

    @Test fun probeBundleSize_slowSynced_failsFast_doesNotHang() = runBlocking {
        // Storyteller lazily generates the whole aligned bundle before answering /synced — for a large
        // cold book that can be minutes. The size probe must NOT inherit the download's unbounded timeout
        // (else the streaming-play path that awaits the sidecar wedges forever, ADR 0028). A bounded
        // sidecar client makes a slow /synced fail fast so the caller falls back.
        val bounded = StorytellerBundleApiImpl(OkHttpClient(), sidecarCallTimeoutSeconds = 1)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", "315074677")
                .setHeadersDelay(10, TimeUnit.SECONDS),
        )

        val start = System.nanoTime()
        val result = (bounded as StorytellerBundleProbeApi).probeBundleSize(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("Expected NetworkError on a slow probe but got $result", result is NetworkResult.Offline)
        assertTrue("Probe should have failed fast (~1s), took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test fun streamSidecar_wedgedSynced_failsWithinTimeout_doesNotHangForever() = runBlocking {
        // A coroutine timeout can't cancel the blocking execute(), so the streaming sidecar fetch relies
        // on a real callTimeout to fail a wedged /synced — otherwise the "Preparing…" indicator sticks
        // forever (ADR 0028). With a 1s bound, a 10s-delayed response must come back as NetworkError fast.
        val bounded = StorytellerBundleApiImpl(OkHttpClient(), sidecarStreamTimeoutSeconds = 1)
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(64))).setHeadersDelay(10, TimeUnit.SECONDS),
        )

        val start = System.nanoTime()
        val result = bounded.streamSidecar(
            baseUrl = server.url("/").toString().trimEnd('/'),
            bookId = "42",
            token = "tkn",
            insecureAllowed = false,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("Expected NetworkError on a wedged /synced but got $result", result is NetworkResult.Offline)
        assertTrue("streamSidecar must give up at its callTimeout (~1s), took ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
