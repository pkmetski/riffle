package com.riffle.core.data

import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.network.createStreamingHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudiobookDownloadRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private object NoopAudiobookRepository : AudiobookRepository {
        override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? = null
        override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) = Unit
    }

    private fun repo(
        root: File,
        cacheRoot: File = tmp.newFolder(),
        audiobookRepository: AudiobookRepository = NoopAudiobookRepository,
        localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    ): AudiobookDownloadRepositoryImpl {
        val httpClient = createStreamingHttpClient()
        val trackDownloader = AudiobookTrackDownloader(httpClient, com.riffle.core.domain.DefaultDispatcherProvider)
        return AudiobookDownloadRepositoryImpl(
            audiobookRepository,
            trackDownloader,
            cacheRoot,
            root,
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

    /** Write a completed download (track files + manifest) for (srv, it) under [root]. */
    private fun writeDownload(root: File) {
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
    fun `isDownloaded reflects the manifest presence`() {
        val root = tmp.newFolder()
        assertFalse(repo(root).isDownloaded("srv", "it"))
        writeDownload(root)
        assertTrue(repo(root).isDownloaded("srv", "it"))
    }

    @Test
    fun `localSession reconstructs file URLs, spans and timeline from the manifest`() {
        val root = tmp.newFolder()
        writeDownload(root)

        val s = repo(root).localSession("srv", "it")!!

        assertEquals(2, s.trackUrls.size)
        assertTrue("file:// URL expected", s.trackUrls[0].startsWith("file:"))
        assertTrue(s.trackUrls[0].endsWith("/srv/it/track-0"))
        assertEquals(listOf(0.0, 100.0), s.tracks.map { it.startOffsetSec })
        assertEquals(300.0, s.timeline.durationSec, 0.0)
        assertEquals(listOf("One", "Two"), s.timeline.chapters.map { it.title })
        assertEquals(0.0, s.serverCurrentTimeSec, 0.0) // resume comes from progress sync, not the manifest
    }

    @Test
    fun `localSession is null when not downloaded`() {
        assertNull(repo(tmp.newFolder()).localSession("srv", "it"))
    }

    @Test
    fun `remove deletes the directory and reports freed bytes`() = runTest {
        val root = tmp.newFolder()
        writeDownload(root)
        val r = repo(root)

        val freed = r.remove("srv", "it")

        assertTrue("freed at least the track bytes", freed >= 30L)
        assertFalse(r.isDownloaded("srv", "it"))
        assertNull(r.localSession("srv", "it"))
    }

    @Test
    fun `download promotes completed cache without opening a network session`() = runTest {
        val downloadsRoot = tmp.newFolder("downloads")
        val cacheRoot = tmp.newFolder("cache")
        writeDownload(cacheRoot)
        val source = object : AudiobookRepository {
            override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession? {
                throw AssertionError("cached audiobook promotion must not open a network session")
            }
            override suspend fun saveProgress(
                sourceId: String,
                itemId: String,
                positionSec: Double,
                durationSec: Double,
            ) = Unit
        }
        val progress = mutableListOf<Pair<Long, Long>>()
        val events = RecordingLocalAvailabilityEvents()
        val r = repo(downloadsRoot, cacheRoot, source, events)

        val result = r.download("srv", "it") { downloaded, total ->
            progress += downloaded to total
        }

        assertEquals(AudiobookDownloadResult.Success, result)
        assertTrue(r.isDownloaded("srv", "it"))
        assertFalse(File(cacheRoot, "srv/it").exists())
        assertTrue(progress.last().first >= 30L)
        assertEquals(progress.last().second, progress.last().first)
        assertEquals(listOf(StoredItemRef("srv", "it")), events.refs)
    }

    @Test
    fun `download streams every track through the Ktor client and writes the manifest last`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("audio-bytes"))
            val session = AudiobookSession(
                trackUrls = listOf(server.url("/track.mp3").toString()),
                tracks = listOf(AudiobookTrackSpan(index = 0, startOffsetSec = 0.0, durationSec = 12.0)),
                timeline = AudiobookTimeline(durationSec = 12.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val source = object : AudiobookRepository {
                override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession = session
                override suspend fun saveProgress(
                    sourceId: String,
                    itemId: String,
                    positionSec: Double,
                    durationSec: Double,
                ) = Unit
            }
            val root = tmp.newFolder()
            val progress = mutableListOf<Pair<Long, Long>>()

            val result = repo(root, audiobookRepository = source).download("srv", "it") { downloaded, total ->
                progress += downloaded to total
            }

            assertEquals(AudiobookDownloadResult.Success, result)
            assertEquals("audio-bytes", File(root, "srv/it/track-0").readText())
            assertTrue(File(root, "srv/it/manifest.json").exists())
            assertEquals(11L to 11L, progress.last())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `download reports progress before a track response finishes`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
            server.enqueue(
                MockResponse()
                    .setBody(okio.Buffer().write(payload))
                    .throttleBody(64 * 1024, 1, TimeUnit.SECONDS),
            )
            val session = AudiobookSession(
                trackUrls = listOf(server.url("/track.mp3").toString()),
                tracks = listOf(AudiobookTrackSpan(index = 0, startOffsetSec = 0.0, durationSec = 12.0)),
                timeline = AudiobookTimeline(durationSec = 12.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val source = object : AudiobookRepository {
                override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession = session
                override suspend fun saveProgress(
                    sourceId: String,
                    itemId: String,
                    positionSec: Double,
                    durationSec: Double,
                ) = Unit
            }
            val firstProgress = CompletableDeferred<Pair<Long, Long>>()

            val download = async(Dispatchers.IO) {
                repo(tmp.newFolder(), audiobookRepository = source).download("srv", "it") { downloaded, total ->
                    firstProgress.complete(downloaded to total)
                }
            }

            val (downloaded, total) = withTimeout(1_500) { firstProgress.await() }

            assertTrue(downloaded in 1 until total)
            assertFalse("download should still be receiving the throttled response", download.isCompleted)
            assertEquals(AudiobookDownloadResult.Success, download.await())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `download reports whole audiobook progress monotonically across tracks`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val firstTrack = ByteArray(70 * 1024)
            val secondTrack = ByteArray(130 * 1024)
            val wholeAudiobookBytes = (firstTrack.size + secondTrack.size).toLong()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(firstTrack)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(secondTrack)))
            val session = AudiobookSession(
                trackUrls = listOf(
                    server.url("/track-1.mp3").toString(),
                    server.url("/track-2.mp3").toString(),
                ),
                tracks = listOf(
                    AudiobookTrackSpan(index = 0, startOffsetSec = 0.0, durationSec = 10.0),
                    AudiobookTrackSpan(index = 1, startOffsetSec = 10.0, durationSec = 20.0),
                ),
                timeline = AudiobookTimeline(durationSec = 30.0, chapters = emptyList()),
                serverCurrentTimeSec = 0.0,
            )
            val source = object : AudiobookRepository {
                override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession = session
                override suspend fun downloadSizeBytes(sourceId: String, itemId: String) = wholeAudiobookBytes
                override suspend fun saveProgress(
                    sourceId: String,
                    itemId: String,
                    positionSec: Double,
                    durationSec: Double,
                ) = Unit
            }
            val progress = mutableListOf<Pair<Long, Long>>()

            val result = repo(tmp.newFolder(), audiobookRepository = source).download("srv", "it") { downloaded, total ->
                progress += downloaded to total
            }

            assertEquals(AudiobookDownloadResult.Success, result)
            assertTrue("expected progress callbacks", progress.isNotEmpty())
            assertTrue("every callback should use the whole-audiobook total", progress.all { it.second == wholeAudiobookBytes })
            assertTrue("100% is only valid after the final track", progress.dropLast(1).all { it.first < it.second })
            assertEquals(wholeAudiobookBytes to wholeAudiobookBytes, progress.last())
        } finally {
            server.shutdown()
        }
    }
}
