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
import com.riffle.core.network.NetworkEpubDownloadResult
import com.riffle.core.network.NetworkItemEbookInoResult
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
        val local = (downloadsStore.get(item.id) ?: cacheStore.get(item.id))?.takeIf { it.isValidPdf() }
        if (local == null) {
            cacheStore.delete(item.id)
        }
        val pdfFile = if (local != null) {
            local
        } else {
            val server = serverRepository.getActive()
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No active server"))
            val token = tokenStorage.getToken(server.id)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No token for server"))
            val ino = item.ebookFileIno ?: run {
                when (val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                    is NetworkItemEbookInoResult.Success -> r.ino
                    is NetworkItemEbookInoResult.NetworkError -> return PdfOpenResult.NetworkError(r.cause)
                }
            }
            when (val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)) {
                is NetworkEpubDownloadResult.Success -> result.body.use { body ->
                    cacheStore.save(item.id, body.byteStream())
                }
                is NetworkEpubDownloadResult.NetworkError -> return PdfOpenResult.NetworkError(result.cause)
            }
        }
        val lastPosition = positionStore.load(item.id)
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
    }

    override suspend fun downloadPdf(item: LibraryItem): PdfDownloadResult {
        if (downloadsStore.get(item.id) != null) return PdfDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.id)
        if (cached != null) {
            cached.inputStream().use { downloadsStore.save(item.id, it) }
            cacheStore.delete(item.id)
            return PdfDownloadResult.Success
        }
        val server = serverRepository.getActive()
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No active server"))
        val token = tokenStorage.getToken(server.id)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No token for server"))
        val ino = item.ebookFileIno ?: run {
            when (val r = api.getItemEbookFileIno(server.url.value, item.id, token, server.insecureConnectionAllowed)) {
                is NetworkItemEbookInoResult.Success -> r.ino
                is NetworkItemEbookInoResult.NetworkError -> return PdfDownloadResult.NetworkError(r.cause)
            }
        }
        return when (val result = api.downloadEpub(server.url.value, item.id, ino, token, server.insecureConnectionAllowed)) {
            is NetworkEpubDownloadResult.Success -> {
                result.body.use { body -> downloadsStore.save(item.id, body.byteStream()) }
                PdfDownloadResult.Success
            }
            is NetworkEpubDownloadResult.NetworkError -> PdfDownloadResult.NetworkError(result.cause)
        }
    }

    override suspend fun removeDownload(itemId: String) {
        downloadsStore.delete(itemId)
    }

    override fun isDownloaded(itemId: String): Boolean = downloadsStore.get(itemId) != null

    override fun isCached(itemId: String): Boolean = cacheStore.get(itemId) != null

    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {
        positionStore.save(itemId, locatorJson)
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
