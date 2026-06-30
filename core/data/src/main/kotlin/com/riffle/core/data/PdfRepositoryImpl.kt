package com.riffle.core.data

import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.errorAsThrowable
import java.io.File

class PdfRepositoryImpl(
    private val api: AbsLibraryApi,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : PdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        val activeServer = serverRepository.getActive()
            ?: return PdfOpenResult.NetworkError(IllegalStateException("No active server"))
        val local = (downloadsStore.get(activeServer.id, item.id) ?: cacheStore.get(activeServer.id, item.id))?.takeIf { it.isValidPdf() }
        if (local == null) {
            cacheStore.delete(activeServer.id, item.id)
        }
        val pdfFile = if (local != null) {
            local
        } else {
            val token = tokenStorage.getToken(activeServer.id)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No token for server"))
            val ino = item.ebookFileIno ?: run {
                val r = api.getItemEbookFileIno(activeServer.url.value, item.id, token, activeServer.insecureConnectionAllowed)
                if (r is NetworkResult.Success) r.value else return PdfOpenResult.NetworkError(r.errorAsThrowable())
            }
            val result = api.downloadEpub(activeServer.url.value, item.id, ino, token, activeServer.insecureConnectionAllowed)
            if (result !is NetworkResult.Success) return PdfOpenResult.NetworkError(result.errorAsThrowable())
            result.value.use { body -> cacheStore.save(activeServer.id, item.id, body.byteStream()) }
        }
        val lastPosition = positionStore.load(activeServer.id, item.id)
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
    }

    override suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PdfDownloadResult {
        if (downloadsStore.get(item.serverId, item.id) != null) return PdfDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.serverId, item.id)
        if (cached != null) {
            val size = cached.length()
            cached.inputStream().use {
                downloadsStore.save(item.serverId, item.id, ProgressReportingInputStream(it, size, onProgress))
            }
            cacheStore.delete(item.serverId, item.id)
            return PdfDownloadResult.Success
        }
        val server = serverRepository.getActive()
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No active server"))
        val token = tokenStorage.getToken(server.id)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No token for server"))
        val ino = item.ebookFileIno ?: run {
            val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)
            if (r is NetworkResult.Success) r.value else return PdfDownloadResult.NetworkError(r.errorAsThrowable())
        }
        val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return PdfDownloadResult.NetworkError(result.errorAsThrowable())
        result.value.use { body ->
            val stream = ProgressReportingInputStream(body.byteStream(), body.contentLength(), onProgress)
            downloadsStore.save(item.serverId, item.id, stream)
        }
        return PdfDownloadResult.Success
    }

    override suspend fun removeDownload(serverId: String, itemId: String) {
        downloadsStore.delete(serverId, itemId)
    }

    override fun isDownloaded(serverId: String, itemId: String): Boolean = downloadsStore.get(serverId, itemId) != null

    override fun isCached(serverId: String, itemId: String): Boolean = cacheStore.get(serverId, itemId) != null

    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {
        val serverId = serverRepository.getActive()?.id ?: return
        positionStore.save(serverId, itemId, locatorJson)
    }
}

private fun File.isValidPdf(): Boolean {
    if (!exists() || length() < 8) return false
    val headerOk = inputStream().use { stream ->
        val header = ByteArray(4).also { stream.read(it) }
        header.contentEquals("%PDF".toByteArray())
    }
    if (!headerOk) return false
    val tailSize = minOf(32L, length()).toInt()
    val tail = ByteArray(tailSize)
    java.io.RandomAccessFile(this, "r").use { raf ->
        raf.seek(length() - tailSize)
        raf.readFully(tail)
    }
    return String(tail).contains("%%EOF")
}
