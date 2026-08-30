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
        val inFlight = activeRequests.incrementAndGet()
        try {
            if (inFlight > 1) {
                overlappingRequests.set(true)
                exchange.sendResponseHeaders(429, -1)
                return
            }
            Thread.sleep(150)
            val body = File(exchange.requestURI.path).name.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            activeRequests.decrementAndGet()
            exchange.close()
        }
    }
}
