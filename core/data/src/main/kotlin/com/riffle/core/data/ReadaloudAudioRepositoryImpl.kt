package com.riffle.core.data

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBundleSizeResult
import com.riffle.core.network.StorytellerBundleProbeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ReadaloudAudioRepositoryImpl(
    private val downloader: AudiobookBundleDownloader,
    private val bundleProbe: StorytellerBundleProbeApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ReadaloudAudioRepository {

    override fun isAudioAvailable(itemId: String): Boolean = bundleFile(itemId) != null

    override fun bundleFile(itemId: String): File? =
        downloadsStore.get(itemId) ?: cacheStore.get(itemId)

    override suspend fun readTrack(itemId: String): ReadaloudTrack? = withContext(Dispatchers.IO) {
        val file = bundleFile(itemId) ?: return@withContext null
        runCatching { MediaOverlayReader.readTrack(file) }
            .getOrNull()
            ?.takeIf { it.clips.isNotEmpty() }
    }

    override suspend fun probeSizeBytes(itemId: String): Long? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return when (val r = bundleProbe.probeBundleSize(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkStorytellerBundleSizeResult.Success -> r.sizeBytes
            is NetworkStorytellerBundleSizeResult.NetworkError -> null
        }
    }

    override suspend fun downloadAudio(
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        val activeId = serverRepository.getActive()?.id
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No active server"))
        return downloadAudio(itemId, activeId, onProgress)
    }

    override suspend fun downloadAudio(
        bookId: String,
        serverId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult {
        if (downloadsStore.get(bookId) != null) return AudioDownloadResult.Success
        val server = serverRepository.getById(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No server $serverId"))
        val token = tokenStorage.getToken(serverId)
            ?: return AudioDownloadResult.NetworkError(IllegalStateException("No token for $serverId"))
        return when (val r = downloader.download(server.url.value, bookId, token, server.insecureConnectionAllowed, onProgress)) {
            is AudiobookBundleDownloader.Result.Success -> AudioDownloadResult.Success
            is AudiobookBundleDownloader.Result.NetworkError -> AudioDownloadResult.NetworkError(r.cause)
        }
    }

    override suspend fun removeAudio(itemId: String): Long = withContext(Dispatchers.IO) {
        val freed = (downloadsStore.get(itemId)?.length() ?: 0L) + (cacheStore.get(itemId)?.length() ?: 0L)
        downloadsStore.delete(itemId)
        cacheStore.delete(itemId)
        freed
    }
}
