package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

const val NS_AUDIOBOOK_CACHE = "audiobook-cache"

/**
 * iOS counterpart to `AudiobookDownloadRepositoryImpl` (ADR 0035), step for step: download every
 * track into a permanent per-item directory, write the shared [AudiobookDownloadManifest] **last**
 * as the atomic completion marker, and rebuild a playable session from it offline. A partial
 * download (tracks, no manifest) reads as not-downloaded and is re-fetched.
 *
 * Also mirrors Android's cache-promotion shortcut: if the background cache already holds the whole
 * book, an explicit download moves those files into the downloads namespace instead of re-fetching.
 */
class IosAudiobookDownloadRepositoryImpl(
    private val audiobookRepository: AudiobookRepository,
    private val trackDownloader: IosAudiobookTrackDownloader,
    private val fileStore: FileStore,
    private val dispatchers: DispatcherProvider,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
) : AudiobookDownloadRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun downloadsRoot() = fileStore.resolve(NS_AUDIOBOOK_DOWNLOADS)
    private fun cacheRoot() = fileStore.resolve(NS_AUDIOBOOK_CACHE)

    private fun itemDir(sourceId: String, itemId: String) =
        IosAudiobookFiles.itemDir(downloadsRoot(), sourceId, itemId)

    private fun cacheItemDir(sourceId: String, itemId: String) =
        IosAudiobookFiles.itemDir(cacheRoot(), sourceId, itemId)

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        IosAudiobookFiles.exists(IosAudiobookFiles.manifestPath(itemDir(sourceId, itemId)))

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val dir = itemDir(sourceId, itemId)
        val manifestText = IosAudiobookFiles.readText(IosAudiobookFiles.manifestPath(dir)) ?: return null
        val manifest = runCatching { json.decodeFromString<AudiobookDownloadManifest>(manifestText) }.getOrNull()
            ?: return null
        return manifest.toSession { fileName -> IosAudiobookFiles.fileUrl("$dir/$fileName") }
    }

    override suspend fun download(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = withContext(dispatchers.io) {
        if (isDownloaded(sourceId, itemId)) return@withContext AudiobookDownloadResult.Success
        if (IosAudiobookFiles.exists(IosAudiobookFiles.manifestPath(cacheItemDir(sourceId, itemId)))) {
            return@withContext promoteCacheToDownload(sourceId, itemId, onProgress)
        }
        val session = audiobookRepository.openSession(sourceId, itemId)
            ?: return@withContext AudiobookDownloadResult.NetworkError(
                IllegalStateException("Could not open play session"),
            )
        val wholeAudiobookBytes = audiobookRepository.downloadSizeBytes(sourceId, itemId)?.takeIf { it > 0L }
        val progress = IosCumulativeDownloadProgress(wholeAudiobookBytes ?: 0L, onProgress)

        val dir = itemDir(sourceId, itemId).also { IosAudiobookFiles.mkdirs(it) }
        // Sources whose streaming format can't be byte-downloaded (e.g. O'Reilly HLS) set
        // downloadTrackUrls; substitute those so the track downloader receives real file URLs.
        val downloadSession = session.downloadTrackUrls?.let { session.copy(trackUrls = it) } ?: session
        try {
            val manifestTracks = trackDownloader.download(downloadSession, dir, progress)
            val manifest = AudiobookDownloadManifest.from(session, manifestTracks)
            IosAudiobookFiles.writeText(IosAudiobookFiles.manifestPath(dir), json.encodeToString(manifest))
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } catch (e: Exception) {
            IosAudiobookFiles.deleteRecursively(dir) // leave no partial download behind
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    private fun promoteCacheToDownload(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult {
        val from = cacheItemDir(sourceId, itemId)
        val to = itemDir(sourceId, itemId)
        val total = IosAudiobookFiles.directorySize(from)
        return if (IosAudiobookFiles.move(from, to)) {
            onProgress(total, total)
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } else {
            IosAudiobookFiles.deleteRecursively(to)
            AudiobookDownloadResult.NetworkError(IllegalStateException("Could not promote cached audiobook"))
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
