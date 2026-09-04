package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.network.createStreamingHttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudiobookCacheRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun repo(
        root: File,
        localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    ): AudiobookCacheRepositoryImpl {
        val httpClient = createStreamingHttpClient()
        val trackDownloader = AudiobookTrackDownloader(httpClient, com.riffle.core.domain.DefaultDispatcherProvider)
        return AudiobookCacheRepositoryImpl(
            root,
            trackDownloader,
            com.riffle.core.domain.DefaultDispatcherProvider,
            localAvailabilityEvents,
        )
    }

    private class RecordingLocalAvailabilityEvents : LocalAvailabilityEvents {
        val refs = mutableListOf<StoredItemRef>()
        override val changes: SharedFlow<StoredItemRef> = MutableSharedFlow()
        override fun notifyChanged(sourceId: String, itemId: String) {
            refs += StoredItemRef(sourceId, itemId)
        }
    }

    private fun writeCache(root: File) {
        val dir = File(root, "srv/it").apply { mkdirs() }
        File(dir, "track-0").writeBytes(ByteArray(10))
        File(dir, "track-1").writeBytes(ByteArray(20))
        val manifest = AudiobookDownloadManifest(
            durationSec = 300.0,
            tracks = listOf(
                AudiobookDownloadManifest.ManifestTrack(0, "track-0", 0.0, 100.0),
                AudiobookDownloadManifest.ManifestTrack(1, "track-1", 100.0, 200.0),
            ),
            chapters = listOf(
                AudiobookDownloadManifest.ManifestChapter(0, 0.0, 100.0, "One"),
                AudiobookDownloadManifest.ManifestChapter(1, 100.0, 300.0, "Two"),
            ),
        )
        File(dir, "manifest.json").writeText(json.encodeToString(manifest))
    }

    @Test
    fun `isCached reflects manifest presence`() {
        val root = tmp.newFolder()
        assertFalse(repo(root).isCached("srv", "it"))
        writeCache(root)
        assertTrue(repo(root).isCached("srv", "it"))
    }

    @Test
    fun `localSession returns null when not cached`() {
        assertNull(repo(tmp.newFolder()).localSession("srv", "it"))
    }

    @Test
    fun `localSession reconstructs file URLs spans and timeline from the manifest`() {
        val root = tmp.newFolder()
        writeCache(root)

        val s = repo(root).localSession("srv", "it")!!

        assertEquals(2, s.trackUrls.size)
        assertTrue("file:// URL expected", s.trackUrls[0].startsWith("file:"))
        assertTrue(s.trackUrls[0].endsWith("/srv/it/track-0"))
        assertEquals(listOf(0.0, 100.0), s.tracks.map { it.startOffsetSec })
        assertEquals(300.0, s.timeline.durationSec, 0.0)
        assertEquals(listOf("One", "Two"), s.timeline.chapters.map { it.title })
        assertEquals(0.0, s.serverCurrentTimeSec, 0.0)
    }

    @Test
    fun `localSession returns null and deletes stale dir for zero-duration (live stream) manifest`() {
        val root = tmp.newFolder()
        val dir = File(root, "srv/s:live").apply { mkdirs() }
        File(dir, "track-0").writeBytes(ByteArray(5))
        val liveManifest = AudiobookDownloadManifest(
            durationSec = 0.0,
            tracks = listOf(AudiobookDownloadManifest.ManifestTrack(0, "track-0", 0.0, 0.0)),
            chapters = emptyList(),
        )
        File(dir, "manifest.json").writeText(json.encodeToString(liveManifest))

        val session = repo(root).localSession("srv", "s:live")

        assertNull("live stream must not produce a local session", session)
        assertFalse("stale cache dir must be removed", dir.exists())
    }

    @Test
    fun `awaitCachedAudiobook downloads all tracks and writes manifest`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            // HEAD pre-scan runs in parallel before downloads; returning 405 tells it the server
            // doesn't support HEAD, so the pre-scan skips gracefully and downloads proceed normally.
            server.dispatcher = object : Dispatcher() {
                private val getQueue = ArrayDeque(listOf("audio-track-0", "audio-track-1"))
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "HEAD") MockResponse().setResponseCode(405)
                    else MockResponse().setBody(getQueue.removeFirst())
            }
            val session = AudiobookSession(
                trackUrls = listOf(
                    server.url("/track-0.mp3").toString(),
                    server.url("/track-1.mp3").toString(),
                ),
                tracks = listOf(
                    AudiobookTrackSpan(0, 0.0, 10.0),
                    AudiobookTrackSpan(1, 10.0, 20.0),
                ),
                timeline = AudiobookTimeline(durationSec = 30.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val root = tmp.newFolder()
            val r = repo(root)

            r.awaitCachedAudiobook("srv", "it", session)

            assertTrue(r.isCached("srv", "it"))
            val s = r.localSession("srv", "it")!!
            assertEquals(2, s.trackUrls.size)
            assertTrue(s.trackUrls[0].startsWith("file:"))
            val allContent = setOf(
                File(root, "srv/it/track-0").readText(),
                File(root, "srv/it/track-1").readText(),
            )
            assertEquals(setOf("audio-track-0", "audio-track-1"), allContent)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `awaitCachedAudiobook notifies local availability changed after manifest is written`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("audio-track-0"))
            val session = AudiobookSession(
                trackUrls = listOf(server.url("/track-0.mp3").toString()),
                tracks = listOf(AudiobookTrackSpan(0, 0.0, 10.0)),
                timeline = AudiobookTimeline(durationSec = 10.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val events = RecordingLocalAvailabilityEvents()
            val r = repo(tmp.newFolder(), events)

            r.awaitCachedAudiobook("srv", "it", session)

            assertEquals(listOf(StoredItemRef("srv", "it")), events.refs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `awaitCachedAudiobook is a no-op when already cached`() = runTest {
        val root = tmp.newFolder()
        writeCache(root)
        val r = repo(root)

        // No server enqueued — if it made a network call this would fail with a connection error.
        r.awaitCachedAudiobook(
            "srv", "it",
            AudiobookSession(
                trackUrls = listOf("http://example.invalid/track.mp3"),
                tracks = listOf(AudiobookTrackSpan(0, 0.0, 10.0)),
                timeline = AudiobookTimeline(10.0, emptyList()),
                serverCurrentTimeSec = 0.0,
            ),
        )

        assertTrue(r.isCached("srv", "it"))
    }

    @Test
    fun `awaitCachedAudiobook silently swallows download failures and leaves no partial dir`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            val session = AudiobookSession(
                trackUrls = listOf(server.url("/track.mp3").toString()),
                tracks = listOf(AudiobookTrackSpan(0, 0.0, 10.0)),
                timeline = AudiobookTimeline(10.0, emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val root = tmp.newFolder()
            val r = repo(root)

            r.awaitCachedAudiobook("srv", "it", session) // must not throw

            assertFalse(r.isCached("srv", "it"))
            assertFalse(File(root, "srv/it").exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `remove deletes the cache dir and reports freed bytes`() = runTest {
        val root = tmp.newFolder()
        writeCache(root)
        val r = repo(root)

        val freed = r.remove("srv", "it")

        assertTrue("freed at least the track bytes", freed >= 30L)
        assertFalse(r.isCached("srv", "it"))
        assertNull(r.localSession("srv", "it"))
    }
}
