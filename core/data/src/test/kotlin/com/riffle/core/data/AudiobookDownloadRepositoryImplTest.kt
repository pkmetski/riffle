package com.riffle.core.data

import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
        override suspend fun openSession(serverId: String, itemId: String): AudiobookSession? = null
        override suspend fun saveProgress(serverId: String, itemId: String, positionSec: Double, durationSec: Double) = Unit
    }

    private fun repo(root: File) = AudiobookDownloadRepositoryImpl(NoopAudiobookRepository, OkHttpClient(), root)

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
}
