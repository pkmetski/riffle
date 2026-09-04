package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.network.createStreamingHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudiobookTrackDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `multi-track download shows deterministic progress when server supports HEAD`() = runTest {
        // Server that responds to HEAD with Content-Length and GET with the actual body.
        // This mirrors podcast CDNs (e.g. Radio.es) that serve static MP3 files.
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                val body = File(exchange.requestURI.path).name.toByteArray()
                when (exchange.requestMethod) {
                    "HEAD" -> {
                        exchange.responseHeaders.set("Content-Length", body.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                    }
                    "GET" -> {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val session = AudiobookSession(
                trackUrls = listOf("$baseUrl/track-0.mp3", "$baseUrl/track-1.mp3"),
                tracks = listOf(
                    AudiobookTrackSpan(0, 0.0, 10.0),
                    AudiobookTrackSpan(1, 10.0, 20.0),
                ),
                timeline = AudiobookTimeline(durationSec = 30.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val progressUpdates = mutableListOf<Pair<Long, Long>>()
            val downloader = AudiobookTrackDownloader(
                createStreamingHttpClient(),
                com.riffle.core.domain.DefaultDispatcherProvider,
            )

            downloader.download(
                session = session,
                dir = tmp.newFolder(),
                progress = CumulativeDownloadProgress(0L, onProgress = { d, t -> progressUpdates.add(d to t) }),
            )

            // HEAD pre-scan must have established a positive total before the first byte flowed.
            // If this assertion flips red, the pre-scan is broken and progress would show a spinner
            // instead of a percentage for any multi-track source without a fingerprint (e.g. Radio.es).
            val expectedTotal = "track-0.mp3".length.toLong() + "track-1.mp3".length.toLong()
            assert(progressUpdates.isNotEmpty()) { "Expected at least one progress callback" }
            assert(progressUpdates.first().second == expectedTotal) {
                "First progress callback must have total=$expectedTotal (from HEAD pre-scan), " +
                    "got total=${progressUpdates.first().second}"
            }
            assert(progressUpdates.all { (_, t) -> t == expectedTotal }) {
                "Total must remain stable throughout the download"
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `multi-track download stays indeterminate when server does not support HEAD`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                // HEAD returns 405 — server doesn't support it
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return@createContext
                }
                val body = File(exchange.requestURI.path).name.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val session = AudiobookSession(
                trackUrls = listOf("$baseUrl/track-0.mp3", "$baseUrl/track-1.mp3"),
                tracks = listOf(
                    AudiobookTrackSpan(0, 0.0, 10.0),
                    AudiobookTrackSpan(1, 10.0, 20.0),
                ),
                timeline = AudiobookTimeline(durationSec = 30.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val progressUpdates = mutableListOf<Pair<Long, Long>>()
            val downloader = AudiobookTrackDownloader(
                createStreamingHttpClient(),
                com.riffle.core.domain.DefaultDispatcherProvider,
            )

            downloader.download(
                session = session,
                dir = tmp.newFolder(),
                progress = CumulativeDownloadProgress(0L, onProgress = { d, t -> progressUpdates.add(d to t) }),
            )

            // Without HEAD support, all progress updates must have total=0 (indeterminate).
            assert(progressUpdates.isNotEmpty())
            assert(progressUpdates.all { (_, t) -> t == 0L }) {
                "Total must remain 0 (indeterminate) when HEAD is not supported"
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `download serializes track requests for servers that reject concurrent pulls`() = runTest {
        val overlappingRequests = AtomicBoolean(false)
        val activeRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                handleTrackRequest(exchange, activeRequests, overlappingRequests)
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val session = AudiobookSession(
                trackUrls = listOf(
                    "$baseUrl/track-0.mp3",
                    "$baseUrl/track-1.mp3",
                ),
                tracks = listOf(
                    AudiobookTrackSpan(0, 0.0, 10.0),
                    AudiobookTrackSpan(1, 10.0, 20.0),
                ),
                timeline = AudiobookTimeline(durationSec = 30.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val downloader = AudiobookTrackDownloader(
                createStreamingHttpClient(),
                com.riffle.core.domain.DefaultDispatcherProvider,
            )

            val tracks = downloader.download(
                session = session,
                dir = tmp.newFolder(),
                progress = CumulativeDownloadProgress(0L, onProgress = { _, _ -> }),
            )

            assertEquals(listOf(0, 1), tracks.map { it.index })
            assertFalse("track requests must not overlap", overlappingRequests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun handleTrackRequest(
        exchange: HttpExchange,
        activeRequests: AtomicInteger,
        overlappingRequests: AtomicBoolean,
    ) {
        val body = File(exchange.requestURI.path).name.toByteArray()
        // HEAD pre-scans are intentionally parallel; only GET downloads must be serial.
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.set("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        val inFlight = activeRequests.incrementAndGet()
        try {
            if (inFlight > 1) {
                overlappingRequests.set(true)
                exchange.sendResponseHeaders(429, -1)
                return
            }
            Thread.sleep(150)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            activeRequests.decrementAndGet()
            exchange.close()
        }
    }
}
