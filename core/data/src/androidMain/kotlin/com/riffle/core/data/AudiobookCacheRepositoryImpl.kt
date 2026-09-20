package com.riffle.core.data

import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.JvmAudiobookCacheRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AudiobookCacheRepositoryImpl constructor(
    private val cacheDir: File,
    private val trackDownloader: AudiobookTrackDownloader,
    private val dispatchers: DispatcherProvider,
    private val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    /**
     * Jittered delay between consecutive track downloads during background caching. Avoids
     * triggering anti-abuse rate limits on CDN-hosted sources (e.g. O'Reilly/Kaltura). Default
     * 1.5–3s is conservative enough to stay unnoticed while completing in reasonable time.
     * Set to 0 in tests to keep them fast.
     */
    private val minInterTrackDelayMs: Long = 1_500L,
    private val maxInterTrackDelayMs: Long = 3_000L,
) : JvmAudiobookCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(sourceId: String, itemId: String) = File(cacheDir, "$sourceId/$itemId")
    private fun manifestFile(sourceId: String, itemId: String) = File(itemDir(sourceId, itemId), MANIFEST)

    override fun isCached(sourceId: String, itemId: String): Boolean =
        manifestFile(sourceId, itemId).exists()

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val mf = manifestFile(sourceId, itemId)
        if (!mf.exists()) return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(mf.readText()) }.getOrNull()
            ?: return null
        // Live streams have durationSec == 0.0 and must not be played from a local file.
        // A stale entry from before this guard was added is cleaned up here.
        if (manifest.durationSec == 0.0) {
            itemDir(sourceId, itemId).deleteRecursively()
            return null
        }
        val dir = itemDir(sourceId, itemId)
        return manifest.toSession { fileName -> File(dir, fileName).toURI().toString() }
    }

    override suspend fun awaitCachedAudiobook(
        sourceId: String,
        itemId: String,
        session: AudiobookSession,
    ) = withContext(dispatchers.io) {
        if (isCached(sourceId, itemId)) return@withContext
        val dir = itemDir(sourceId, itemId).apply { mkdirs() }
        // Sources whose streaming format can't be byte-downloaded (e.g. O'Reilly HLS) set
        // downloadTrackUrls; substitute those so the track downloader receives real file URLs.
        val downloadSession = session.downloadTrackUrls
            ?.let { session.copy(trackUrls = it) }
            ?: session
        try {
            val noop: (Long, Long) -> Unit = { _, _ -> }
            val progress = CumulativeDownloadProgress(0L, noop)
            val interTrackDelay = if (minInterTrackDelayMs >= maxInterTrackDelayMs) minInterTrackDelayMs
            else minInterTrackDelayMs + kotlin.random.Random.nextLong(maxInterTrackDelayMs - minInterTrackDelayMs + 1)
            val manifestTracks = trackDownloader.download(downloadSession, dir, progress, interTrackDelay)
            val manifest = AudiobookDownloadManifest.from(session, manifestTracks)
            // Written last → atomic completion marker (same pattern as AudiobookDownloadRepositoryImpl).
            manifestFile(sourceId, itemId).writeText(json.encodeToString(manifest))
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
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
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
        freed
    }
}
