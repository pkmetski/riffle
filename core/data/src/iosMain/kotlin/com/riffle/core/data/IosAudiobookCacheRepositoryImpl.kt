package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * iOS counterpart to `AudiobookCacheRepositoryImpl`: opportunistically caches a streaming book's
 * tracks in the background so the next open plays locally. Any failure is swallowed — streaming is
 * unaffected and the cache is retried on the next open — and a cancelled cache leaves no partial
 * directory behind.
 */
class IosAudiobookCacheRepositoryImpl(
    private val trackDownloader: IosAudiobookTrackDownloader,
    private val fileStore: FileStore,
    private val dispatchers: DispatcherProvider,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
    /**
     * Jittered delay between consecutive track downloads during background caching, matching
     * Android: avoids tripping anti-abuse rate limits on CDN-hosted sources. Set to 0 in tests.
     */
    private val minInterTrackDelayMs: Long = 1_500L,
    private val maxInterTrackDelayMs: Long = 3_000L,
) : AudiobookCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(sourceId: String, itemId: String) =
        IosAudiobookFiles.itemDir(fileStore.resolve(NS_AUDIOBOOK_CACHE), sourceId, itemId)

    override fun isCached(sourceId: String, itemId: String): Boolean =
        IosAudiobookFiles.exists(IosAudiobookFiles.manifestPath(itemDir(sourceId, itemId)))

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val dir = itemDir(sourceId, itemId)
        val manifestText = IosAudiobookFiles.readText(IosAudiobookFiles.manifestPath(dir)) ?: return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(manifestText) }.getOrNull()
            ?: return null
        // Live streams have durationSec == 0.0 and must not be played from a local file.
        // A stale entry from before this guard was added is cleaned up here.
        if (manifest.durationSec == 0.0) {
            IosAudiobookFiles.deleteRecursively(dir)
            return null
        }
        return manifest.toSession { fileName -> IosAudiobookFiles.fileUrl("$dir/$fileName") }
    }

    override suspend fun awaitCachedAudiobook(
        sourceId: String,
        itemId: String,
        session: AudiobookSession,
    ) = withContext(dispatchers.io) {
        if (isCached(sourceId, itemId)) return@withContext
        val dir = itemDir(sourceId, itemId).also { IosAudiobookFiles.mkdirs(it) }
        val downloadSession = session.downloadTrackUrls?.let { session.copy(trackUrls = it) } ?: session
        try {
            val progress = IosCumulativeDownloadProgress(0L) { _, _ -> }
            val interTrackDelay = if (minInterTrackDelayMs >= maxInterTrackDelayMs) {
                minInterTrackDelayMs
            } else {
                minInterTrackDelayMs + Random.nextLong(maxInterTrackDelayMs - minInterTrackDelayMs + 1)
            }
            val manifestTracks = trackDownloader.download(downloadSession, dir, progress, interTrackDelay)
            val manifest = AudiobookDownloadManifest.from(session, manifestTracks)
            // Written last → atomic completion marker (same pattern as the download repository).
            IosAudiobookFiles.writeText(IosAudiobookFiles.manifestPath(dir), json.encodeToString(manifest))
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
        } catch (e: CancellationException) {
            IosAudiobookFiles.deleteRecursively(dir)
            throw e
        } catch (_: Exception) {
            IosAudiobookFiles.deleteRecursively(dir)
            // Streaming continues unaffected; cache will be retried on next open.
        }
    }

    override suspend fun remove(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val dir = itemDir(sourceId, itemId)
        val freed = IosAudiobookFiles.directorySize(dir)
        IosAudiobookFiles.deleteRecursively(dir)
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
        freed
    }
}
