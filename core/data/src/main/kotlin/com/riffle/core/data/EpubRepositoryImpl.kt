package com.riffle.core.data

import com.riffle.core.domain.EpubCacheManager
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkEpubDownloadResult
import com.riffle.core.network.NetworkItemEbookInoResult
import javax.inject.Inject

class EpubRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val cacheManager: EpubCacheManager,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : EpubRepository {

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        val cached = cacheManager.getCachedEpub(item.id)
        val epubFile = if (cached != null) {
            cached
        } else {
            val server = serverRepository.getActive()
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No active server"))
            val token = tokenStorage.getToken(server.id)
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No token for server"))
            val ino = item.ebookFileIno ?: run {
                when (val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                    is NetworkItemEbookInoResult.Success -> r.ino
                    is NetworkItemEbookInoResult.NetworkError -> return EpubOpenResult.NetworkError(r.cause)
                }
            }
            when (val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)) {
                is NetworkEpubDownloadResult.Success -> cacheManager.cacheEpub(item.id, result.bytes)
                is NetworkEpubDownloadResult.NetworkError -> return EpubOpenResult.NetworkError(result.cause)
            }
        }
        val lastPosition = positionStore.load(item.id)
        return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
    }

    override suspend fun saveReadingPosition(itemId: String, cfi: String) {
        positionStore.save(itemId, cfi)
    }
}
