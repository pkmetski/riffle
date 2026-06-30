package com.riffle.core.data

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerBundleProbeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class ReadaloudAudioRepositoryImpl(
    private val downloader: AudiobookBundleDownloader,
    private val bundleProbe: StorytellerBundleProbeApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ReadaloudAudioRepository {

    // Process-level cache: keyed by (serverId, itemId, file.lastModified()) so a re-downloaded bundle
    // gets a fresh parse while repeated opens of the same bundle return instantly (~0ms vs ~1.8s).
    private val trackCache = ConcurrentHashMap<Triple<String, String, Long>, ReadaloudTrack>()

    override fun isAudioAvailable(serverId: String, itemId: String): Boolean = bundleFile(serverId, itemId) != null

    override fun bundleFile(serverId: String, itemId: String): File? =
        downloadsStore.get(serverId, itemId) ?: cacheStore.get(serverId, itemId)

    override suspend fun readTrack(serverId: String, itemId: String): ReadaloudTrack? = withContext(Dispatchers.IO) {
        val file = bundleFile(serverId, itemId) ?: return@withContext null
        val key = Triple(serverId, itemId, file.lastModified())
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

    override suspend fun probeSizeBytes(serverId: String, itemId: String): Long? {
        val server = serverRepository.getById(serverId) ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        val r = bundleProbe.probeBundleSize(server.url.value, itemId, token, server.insecureConnectionAllowed)
        return (r as? NetworkResult.Success)?.value
    }

    override suspend fun downloadAudio(
        serverId: String,
        bookId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        if (downloadsStore.get(serverId, bookId) != null) return AudioDownloadResult.Success
        val server = serverRepository.getById(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No server $serverId"))
        val token = tokenStorage.getToken(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No token for $serverId"))
        return when (val r = downloader.download(serverId, server.url.value, bookId, token, server.insecureConnectionAllowed, onProgress)) {
            is AudiobookBundleDownloader.Result.Success -> AudioDownloadResult.Success
            is AudiobookBundleDownloader.Result.NetworkError -> AudioDownloadResult.NetworkError(r.cause)
        }
    }

    override suspend fun removeAudio(serverId: String, itemId: String): Long = withContext(Dispatchers.IO) {
        val freed = (downloadsStore.get(serverId, itemId)?.length() ?: 0L) + (cacheStore.get(serverId, itemId)?.length() ?: 0L)
        downloadsStore.delete(serverId, itemId)
        cacheStore.delete(serverId, itemId)
        freed
    }
}
