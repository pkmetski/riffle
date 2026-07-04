package com.riffle.core.data

import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.errorAsThrowable

class EpubRepositoryImpl(
    private val api: AbsLibraryApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : EpubRepository {

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        // Resolve the item's OWN server row, not `getActive()`. A user-switch on the same URL mints
        // a fresh server row (ServerRepositoryImpl.commit → UUID.randomUUID), leaving the previous
        // row (and its cached files under cacheDir/epubs/<oldId>/) in place. Keying by activeServer
        // here made the opener look under the new row's dir, miss the truly-cached file, and fall
        // into the network branch — surfacing "unable to resolve host" when offline even though the
        // library detail badge (which correctly keys by item.serverId) said the book was cached.
        val server = serverRepository.getById(item.serverId)
            ?: return EpubOpenResult.NetworkError(IllegalStateException("No server for item"))
        val local = downloadsStore.get(item.serverId, item.id) ?: cacheStore.get(item.serverId, item.id)
        val epubFile = if (local != null) {
            local
        } else {
            val token = tokenStorage.getToken(server.id)
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No token for server"))
            val ino = item.ebookFileIno ?: run {
                val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)
                if (r is NetworkResult.Success) r.value else return EpubOpenResult.NetworkError(r.errorAsThrowable())
            }
            val download = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)
            if (download !is NetworkResult.Success) return EpubOpenResult.NetworkError(download.errorAsThrowable())
            download.value.use { body -> cacheStore.save(item.serverId, item.id, body.byteStream()) }
        }
        // Position load intentionally stays keyed by activeServer.id — [saveReadingPosition] also
        // uses it, so this pair stays consistent within a session. Round-trip across a user-switch
        // is a separate concern tracked apart from this fix.
        val activeServer = serverRepository.getActive()
        val lastPosition = activeServer?.let { positionStore.load(it.id, item.id) }
        return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
    }

    override suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): EpubDownloadResult {
        if (downloadsStore.get(item.serverId, item.id) != null) return EpubDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.serverId, item.id)
        if (cached != null) {
            val size = cached.length()
            cached.inputStream().use {
                downloadsStore.save(item.serverId, item.id, ProgressReportingInputStream(it, size, onProgress))
            }
            cacheStore.delete(item.serverId, item.id)
            return EpubDownloadResult.Success
        }
        // Same rationale as [openEpub]: resolve by item.serverId so a user-switch (or any second
        // ServerEntity row for the same URL) still fetches from the item's owning server.
        val server = serverRepository.getById(item.serverId)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No server for item"))
        val token = tokenStorage.getToken(server.id)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No token for server"))
        val ino = item.ebookFileIno ?: run {
            val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)
            if (r is NetworkResult.Success) r.value else return EpubDownloadResult.NetworkError(r.errorAsThrowable())
        }
        val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return EpubDownloadResult.NetworkError(result.errorAsThrowable())
        result.value.use { body ->
            val stream = ProgressReportingInputStream(body.byteStream(), body.contentLength(), onProgress)
            downloadsStore.save(item.serverId, item.id, stream)
        }
        return EpubDownloadResult.Success
    }

    override suspend fun removeDownload(serverId: String, itemId: String) {
        downloadsStore.delete(serverId, itemId)
    }

    override fun isDownloaded(serverId: String, itemId: String): Boolean = downloadsStore.get(serverId, itemId) != null

    override fun isCached(serverId: String, itemId: String): Boolean = cacheStore.get(serverId, itemId) != null

    override suspend fun saveReadingPosition(itemId: String, cfi: String) {
        val serverId = serverRepository.getActive()?.id ?: return
        positionStore.save(serverId, itemId, cfi)
    }

    override suspend fun loadLastPositionHref(serverId: String, itemId: String): String? {
        val json = positionStore.load(serverId, itemId) ?: return null
        return runCatching { org.json.JSONObject(json).getString("href").trimStart('/') }.getOrNull()
    }
}
