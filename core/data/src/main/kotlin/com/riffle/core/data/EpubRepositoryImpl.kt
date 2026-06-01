package com.riffle.core.data

import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkEpubDownloadResult
import com.riffle.core.network.NetworkItemEbookInoResult
import com.riffle.core.network.NetworkStorytellerBundleSizeResult
import com.riffle.core.network.StorytellerBundleProbeApi

class EpubRepositoryImpl(
    private val api: AbsLibraryApi,
    private val bundleFetcher: EpubBundleFetcher,
    private val bundleProbe: StorytellerBundleProbeApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : EpubRepository {

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        val activeServer = serverRepository.getActive()
            ?: return EpubOpenResult.NetworkError(IllegalStateException("No active server"))
        val local = downloadsStore.get(item.id) ?: cacheStore.get(item.id)
        val epubFile = if (local != null) {
            local
        } else {
            val token = tokenStorage.getToken(activeServer.id)
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No token for server"))
            when (activeServer.serverType) {
                ServerType.AUDIOBOOKSHELF -> {
                    val ino = item.ebookFileIno ?: run {
                        when (val r = api.getItemEbookFileIno(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)) {
                            is NetworkItemEbookInoResult.Success -> r.ino
                            is NetworkItemEbookInoResult.NetworkError -> return EpubOpenResult.NetworkError(r.cause)
                        }
                    }
                    when (val result = api.downloadEpub(activeServer.url.value, item.id, ino, token, activeServer.insecureConnectionAllowed)) {
                        is NetworkEpubDownloadResult.Success -> result.body.use { body ->
                            cacheStore.save(item.id, body.byteStream())
                        }
                        is NetworkEpubDownloadResult.NetworkError -> return EpubOpenResult.NetworkError(result.cause)
                    }
                }
                ServerType.STORYTELLER -> {
                    // Storyteller's only EPUB endpoint serves the media-aligned bundle (hundreds
                    // of MB for any book with audio). Probe Content-Length first; refuse implicit
                    // cache-on-open above MAX_STORYTELLER_IMPLICIT_CACHE_BYTES so we never silently
                    // burn the user's data. Explicit downloadEpub bypasses this — the user opted in.
                    when (val probe = bundleProbe.probeBundleSize(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)) {
                        is NetworkStorytellerBundleSizeResult.Success ->
                            if (probe.sizeBytes > MAX_STORYTELLER_IMPLICIT_CACHE_BYTES) {
                                return EpubOpenResult.BundleTooLarge(probe.sizeBytes)
                            }
                        is NetworkStorytellerBundleSizeResult.NetworkError ->
                            return EpubOpenResult.NetworkError(probe.cause)
                    }
                    when (val r = bundleFetcher.fetch(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)) {
                        is EpubBundleFetcher.Result.Success -> {
                            try {
                                r.epubFile.inputStream().use { cacheStore.save(item.id, it) }
                            } finally {
                                r.epubFile.delete()
                            }
                        }
                        is EpubBundleFetcher.Result.NetworkError -> return EpubOpenResult.NetworkError(r.cause)
                    }
                }
            }
        }
        val lastPosition = positionStore.load(activeServer.id, item.id)
        return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
    }

    companion object {
        // 50 MB threshold for implicit cache-on-open of Storyteller bundles. Plain ABS EPUBs
        // are typically <10 MB; small Storyteller bundles fit; multi-hundred-MB media bundles
        // require the user to tap Download explicitly.
        internal const val MAX_STORYTELLER_IMPLICIT_CACHE_BYTES = 50L * 1024 * 1024
    }

    override suspend fun downloadEpub(item: LibraryItem): EpubDownloadResult {
        if (downloadsStore.get(item.id) != null) return EpubDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.id)
        if (cached != null) {
            cached.inputStream().use { downloadsStore.save(item.id, it) }
            cacheStore.delete(item.id)
            return EpubDownloadResult.Success
        }
        val server = serverRepository.getActive()
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No active server"))
        val token = tokenStorage.getToken(server.id)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No token for server"))
        return when (server.serverType) {
            ServerType.STORYTELLER -> {
                when (val r = bundleFetcher.fetch(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                    is EpubBundleFetcher.Result.Success -> {
                        try {
                            r.epubFile.inputStream().use { downloadsStore.save(item.id, it) }
                        } finally {
                            r.epubFile.delete()
                        }
                        EpubDownloadResult.Success
                    }
                    is EpubBundleFetcher.Result.NetworkError -> EpubDownloadResult.NetworkError(r.cause)
                }
            }
            ServerType.AUDIOBOOKSHELF -> {
                val ino = item.ebookFileIno ?: run {
                    when (val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                        is NetworkItemEbookInoResult.Success -> r.ino
                        is NetworkItemEbookInoResult.NetworkError -> return EpubDownloadResult.NetworkError(r.cause)
                    }
                }
                when (val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)) {
                    is NetworkEpubDownloadResult.Success -> {
                        result.body.use { body -> downloadsStore.save(item.id, body.byteStream()) }
                        EpubDownloadResult.Success
                    }
                    is NetworkEpubDownloadResult.NetworkError -> EpubDownloadResult.NetworkError(result.cause)
                }
            }
        }
    }

    override suspend fun removeDownload(itemId: String) {
        downloadsStore.delete(itemId)
    }

    override fun isDownloaded(itemId: String): Boolean = downloadsStore.get(itemId) != null

    override fun isCached(itemId: String): Boolean = cacheStore.get(itemId) != null

    override suspend fun saveReadingPosition(itemId: String, cfi: String) {
        val serverId = serverRepository.getActive()?.id ?: return
        positionStore.save(serverId, itemId, cfi)
    }
}
