package com.riffle.core.data

import com.riffle.core.data.di.AudiobookCacheDir
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

class AudiobookCacheRepositoryImpl @Inject constructor(
    @AudiobookCacheDir private val cacheDir: File,
    private val trackDownloader: AudiobookTrackDownloader,
    private val dispatchers: DispatcherProvider,
) : AudiobookCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(sourceId: String, itemId: String) = File(cacheDir, "$sourceId/$itemId")
    private fun manifestFile(sourceId: String, itemId: String) = File(itemDir(sourceId, itemId), "manifest.json")

    override fun isCached(sourceId: String, itemId: String): Boolean =
        manifestFile(sourceId, itemId).exists()

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val mf = manifestFile(sourceId, itemId)
        if (!mf.exists()) return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(mf.readText()) }.getOrNull()
            ?: return null
        val dir = itemDir(sourceId, itemId)
        return AudiobookSession(
            trackUrls = manifest.tracks.map { File(dir, it.file).toURI().toString() },
            tracks = manifest.tracks.map { AudiobookTrackSpan(it.index, it.startOffsetSec, it.durationSec) },
            timeline = AudiobookTimeline(
                durationSec = manifest.durationSec,
                chapters = manifest.chapters.map { AudiobookChapter(it.index, it.startSec, it.endSec, it.title) },
            ),
            serverCurrentTimeSec = 0.0,
        )
    }

    override suspend fun awaitCachedAudiobook(
        sourceId: String,
        itemId: String,
        session: AudiobookSession,
    ) = withContext(dispatchers.io) {
        if (isCached(sourceId, itemId)) return@withContext
        val dir = itemDir(sourceId, itemId).apply { mkdirs() }
        try {
            val noop: (Long, Long) -> Unit = { _, _ -> }
            val progress = CumulativeDownloadProgress(0L, noop)
            val manifestTracks = trackDownloader.download(session, dir, progress)
            val manifest = AudiobookDownloadManifest(
                durationSec = session.timeline.durationSec,
                tracks = manifestTracks.sortedBy { it.index },
                chapters = session.timeline.chapters.map {
                    AudiobookDownloadManifest.ManifestChapter(it.index, it.startSec, it.endSec, it.title)
                },
            )
            // Written last → atomic completion marker (same pattern as AudiobookDownloadRepositoryImpl).
            manifestFile(sourceId, itemId).writeText(json.encodeToString(manifest))
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            // Streaming continues unaffected; cache will be retried on next open.
        }
    }

    override suspend fun remove(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val dir = itemDir(sourceId, itemId)
        val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        freed
    }
}
