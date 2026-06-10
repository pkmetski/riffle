package com.riffle.app.feature.reader.readaloud

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves real streaming audio actually decodes and clocks on-device (ADR 0028): a real MP3 served
 * over the streaming data-source path (cache + clipping + scheme dispatch), played through ExoPlayer,
 * must advance the playback position. This is the on-device check that the streaming audio pipeline
 * works end-to-end — independent of the media-server.
 */
@RunWith(AndroidJUnit4::class)
class StreamingPlaybackAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appCtx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var mp3: ByteArray
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        mp3 = instrumentation.context.assets.open("test_tone.mp3").use { it.readBytes() }
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
    fun tearDown() {
        instrumentation.runOnMainSync { player?.release() }
        SharedBundle.streaming = null
        server.shutdown()
    }

    @Test
    fun streaming_playback_advances_position() {
        val url = server.url("/audio").toString()
        instrumentation.runOnMainSync {
            // streaming context must be set so the dispatch routes http → the cache/HTTP source.
            SharedBundle.streaming = SharedBundle.Streaming(StreamingAudioCache.dataSourceFactory(appCtx, "tok"), emptyMap())
            val p = ExoPlayer.Builder(appCtx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(SharedBundle.dataSourceFactory()))
                .build()
            p.setMediaItem(
                MediaItem.Builder()
                    .setUri(url)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(5000)
                            .build(),
                    )
                    .build(),
            )
            p.prepare()
            p.playWhenReady = true
            player = p
        }

        var pos = 0L
        var state = 0
        repeat(40) {
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                pos = player!!.currentPosition
                state = player!!.playbackState
            }
            if (pos > 1000) return@repeat
        }
        assertTrue("streamed audio must decode and advance position; was ${pos}ms (state=$state)", pos > 1000)
    }
}
