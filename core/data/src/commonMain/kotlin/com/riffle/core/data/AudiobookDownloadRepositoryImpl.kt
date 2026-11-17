package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import io.ktor.client.HttpClient
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Downloads an [com.riffle.core.domain.Audiobook]'s tracks to a permanent per-item directory and
 * reconstructs a playable [AudiobookSession] from them offline (ADR 0035). The directory holds one
 * file per track plus `manifest.json`; the manifest is written **last**, so its presence is the
 * atomic "fully downloaded" marker — a partial download (some tracks, no manifest) reads as
 * not-downloaded and is simply re-fetched. Track transfer is delegated to [AudiobookTrackDownloader].
 *
 * On-disk layout: `<downloadsRoot>/<sourceId>/<itemId>/{track-0, track-1, …, manifest.json}`
 */
class AudiobookDownloadRepositoryImpl(
    private val audiobookRepository: AudiobookRepository,
    private val fileStore: FileStore,
    private val dispatchers: DispatcherProvider,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
    httpClient: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : AudiobookDownloadRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val trackDownloader = AudiobookTrackDownloader(httpClient, dispatchers, fileSystem)

    private fun itemDir(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_AUDIOBOOK_DOWNLOADS, "$sourceId/$itemId")

    private fun cacheItemDir(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_AUDIOBOOK_CACHE, "$sourceId/$itemId")

    private fun manifestPath(dir: String): String = AudiobookFilenames.manifestIn(dir)

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        fileSystem.exists(manifestPath(itemDir(sourceId, itemId)).toPath())

    override fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val dir = itemDir(sourceId, itemId)
        val manifestFile = manifestPath(dir).toPath()
        if (!fileSystem.exists(manifestFile)) return null
        val manifestText = runCatching {
            fileSystem.read(manifestFile) { readUtf8() }
        }.getOrNull() ?: return null
        val manifest = runCatching {
            json.decodeFromString<AudiobookDownloadManifest>(manifestText)
        }.getOrNull() ?: return null
        return manifest.toSession { fileName -> "file://$dir/$fileName" }
    }

    override suspend fun download(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = withContext(dispatchers.io) {
        if (isDownloaded(sourceId, itemId)) return@withContext AudiobookDownloadResult.Success
        val cacheDir = cacheItemDir(sourceId, itemId)
        if (fileSystem.exists(manifestPath(cacheDir).toPath())) {
            return@withContext promoteCacheToDownload(sourceId, itemId, onProgress)
        }
        val session = audiobookRepository.openSession(sourceId, itemId)
            ?: return@withContext AudiobookDownloadResult.NetworkError(
                IllegalStateException("Could not open play session"),
            )
        val wholeAudiobookBytes = audiobookRepository.downloadSizeBytes(sourceId, itemId)?.takeIf { it > 0L }
        val progress = CumulativeDownloadProgress(wholeAudiobookBytes ?: 0L, onProgress)

        val dir = itemDir(sourceId, itemId)
        fileSystem.createDirectories(dir.toPath())

        val downloadSession = session.downloadTrackUrls?.let { session.copy(trackUrls = it) } ?: session
        return@withContext try {
            val manifestTracks = trackDownloader.download(downloadSession, dir, progress)
            val manifest = AudiobookDownloadManifest.from(session, manifestTracks)
            fileSystem.write(manifestPath(dir).toPath()) { writeUtf8(json.encodeToString(manifest)) }
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } catch (e: Exception) {
            fileSystem.deleteRecursively(dir.toPath())
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    private fun promoteCacheToDownload(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult {
        val from = cacheItemDir(sourceId, itemId).toPath()
        val to = itemDir(sourceId, itemId).toPath()
        return try {
            fileSystem.deleteRecursively(to)
            to.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.atomicMove(from, to)
            val total = directorySize(to.toString())
            onProgress(total, total)
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            AudiobookDownloadResult.Success
        } catch (e: Exception) {
            runCatching { fileSystem.deleteRecursively(to) }
            AudiobookDownloadResult.NetworkError(e)
        }
    }

    override suspend fun remove(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val dir = itemDir(sourceId, itemId).toPath()
        val freed = directorySize(dir.toString())
        fileSystem.deleteRecursively(dir)
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
        freed
    }

    private fun directorySize(dirPath: String): Long {
        val path = dirPath.toPath()
        return if (!fileSystem.exists(path)) {
            0L
        } else {
            fileSystem.listRecursively(path).sumOf { p ->
                if (fileSystem.metadata(p).isRegularFile) fileSystem.metadata(p).size ?: 0L else 0L
            }
        }
    }
}
