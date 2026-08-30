package com.riffle.core.data

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerBundleProbeApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class ReadaloudAudioRepositoryImpl(
    private val downloader: AudiobookBundleDownloader,
    private val bundleProbe: StorytellerBundleProbeApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val dispatchers: DispatcherProvider,
) : ReadaloudAudioRepository {

    // Process-level cache: keyed by (sourceId, itemId, file.lastModified()) so a re-downloaded bundle
    // gets a fresh parse while repeated opens of the same bundle return instantly (~0ms vs ~1.8s).
    private val trackCache = ConcurrentHashMap<Triple<String, String, Long>, ReadaloudTrack>()

    override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = bundleFile(sourceId, itemId) != null

    override fun bundleFile(sourceId: String, itemId: String): File? =
        downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId)

    override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = withContext(dispatchers.io) {
        val file = bundleFile(sourceId, itemId) ?: return@withContext null
        val key = Triple(sourceId, itemId, file.lastModified())
        trackCache[key]?.let { return@withContext it }
        val track = parseTrack(file) ?: return@withContext null
        trackCache[key] = track
        track
    }

    // Test seam: subclasses can supply a fake parser without touching the zip on disk.
    protected open fun parseTrack(file: File): ReadaloudTrack? =
        runCatching { MediaOverlayReader.readTrack(file) }
            .getOrNull()
            ?.takeIf { it.clips.isNotEmpty() }

    override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? {
        val server = sourceRepository.getById(sourceId) ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        val r = bundleProbe.probeBundleSize(server.url.value, itemId, token, server.insecureConnectionAllowed)
        return (r as? NetworkResult.Success)?.value
    }

    override suspend fun downloadAudio(
        sourceId: String,
        bookId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        if (downloadsStore.get(sourceId, bookId) != null) return AudioDownloadResult.Success
        val server = sourceRepository.getById(sourceId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No server $sourceId"))
        val token = tokenStorage.getToken(sourceId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No token for $sourceId"))
        return when (val r = downloader.download(sourceId, server.url.value, bookId, token, server.insecureConnectionAllowed, onProgress)) {
            is AudiobookBundleDownloader.Result.Success -> AudioDownloadResult.Success
            is AudiobookBundleDownloader.Result.NetworkError -> AudioDownloadResult.NetworkError(r.cause)
        }
    }

    override suspend fun removeAudio(sourceId: String, itemId: String): Long = withContext(dispatchers.io) {
        val freed = (downloadsStore.get(sourceId, itemId)?.length() ?: 0L) + (cacheStore.get(sourceId, itemId)?.length() ?: 0L)
        downloadsStore.delete(sourceId, itemId)
        cacheStore.delete(sourceId, itemId)
        freed
    }
}
