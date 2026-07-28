package com.riffle.core.data

import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.riffle.core.network.createStreamingHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        audiobookRepository: AudiobookRepository = NoopAudiobookRepository,
    ) = AudiobookDownloadRepositoryImpl(
        audiobookRepository,
        createStreamingHttpClient(),
        root,
        com.riffle.core.domain.DefaultDispatcherProvider,
    )

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

            val result = repo(root, source).download("srv", "it") { downloaded, total ->
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
}
