package com.riffle.app.feature.reader.readaloud

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.data.StreamingMediaItem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device check (ADR 0040) that "Download readaloud" eagerly fills the audio cache: after a
 * download the cached bytes for the track equal its full size, so the book plays offline.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StreamingAudioDownloaderAndroidTest {

    private val appCtx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var mp3: ByteArray

    @Before
    fun setUp() {
        mp3 = InstrumentationRegistry.getInstrumentation().context.assets.open("test_tone.mp3").use { it.readBytes() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range != null) {
                    val spec = range.substringAfter("bytes=").split("-")
                    val start = spec[0].toInt()
                    val end = spec.getOrNull(1)?.toIntOrNull()?.coerceAtMost(mp3.size - 1) ?: (mp3.size - 1)
                    MockResponse().setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $start-$end/${mp3.size}")
                        .setBody(Buffer().write(mp3.copyOfRange(start, end + 1)))
                } else {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Length", mp3.size.toString())
                        .setBody(Buffer().write(mp3))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun download_fills_the_cache_with_the_whole_track() = runBlocking {
        val url = server.url("/audio").toString()
        val items = listOf(StreamingMediaItem("seg", url, 0, 5000))
        var lastProgress = 0f

        StreamingAudioDownloader.download(appCtx, items, "tok", kotlinx.coroutines.Dispatchers.IO) { lastProgress = it }

        assertTrue("progress should reach 1.0, was $lastProgress", lastProgress >= 1f)
        val cached = StreamingAudioCache.get(appCtx).getCachedBytes(url, 0, Long.MAX_VALUE)
        assertTrue("the whole track should be cached; cached=$cached of ${mp3.size}", cached >= mp3.size)
    }

    /**
     * Regression: transient connection drops during a download must be retried automatically.
     * Before the fix, any IOException from [CacheWriter.cache] failed the whole download and required
     * a manual retry tap — requiring 20 taps for a large multi-track book.
     *
     * Uses DISCONNECT_AFTER_REQUEST (server closes connection before sending a response) rather than
     * a 503 status code. A 503 is an explicit HTTP response code — [InvalidResponseCodeException] —
     * and is deliberately NOT retried. A connection drop produces a plain [IOException] and IS retried.
     */
    @Test
    fun download_retries_on_transient_server_error_and_fills_cache() = runBlocking {
        val failCount = AtomicInteger(3)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failCount.getAndDecrement() > 0) {
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                val range = request.getHeader("Range")
                return if (range != null) {
                    val spec = range.substringAfter("bytes=").split("-")
                    val start = spec[0].toInt()
                    val end = spec.getOrNull(1)?.toIntOrNull()?.coerceAtMost(mp3.size - 1) ?: (mp3.size - 1)
                    MockResponse().setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $start-$end/${mp3.size}")
                        .setBody(Buffer().write(mp3.copyOfRange(start, end + 1)))
                } else {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Length", mp3.size.toString())
                        .setBody(Buffer().write(mp3))
                }
            }
        }

        val url = server.url("/retry-audio").toString()
        val items = listOf(StreamingMediaItem("seg", url, 0, 5000))
        var lastProgress = 0f

        StreamingAudioDownloader.download(
            context = appCtx, items = items, bearerToken = "tok",
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            retryBaseDelayMs = 0L,
        ) { lastProgress = it }

        assertTrue("progress should reach 1.0 despite retries, was $lastProgress", lastProgress >= 1f)
        val cached = StreamingAudioCache.get(appCtx).getCachedBytes(url, 0, Long.MAX_VALUE)
        assertTrue("the whole track should be cached after retries; cached=$cached of ${mp3.size}", cached >= mp3.size)
    }
}
