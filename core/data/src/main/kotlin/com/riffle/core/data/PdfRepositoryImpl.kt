package com.riffle.core.data

import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.PdfCacheManager
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkEpubDownloadResult
import com.riffle.core.network.NetworkItemEbookInoResult
import java.io.File
import javax.inject.Inject

class PdfRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val cacheManager: PdfCacheManager,
    private val positionStore: ReadingPositionStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : PdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        val cached = cacheManager.getCachedPdf(item.id)?.takeIf { it.isValidPdf() }
        if (cached == null) cacheManager.evictCachedPdf(item.id)
        val pdfFile = if (cached != null) {
            cached
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
                    cacheManager.cachePdf(item.id, body.byteStream())
                }
                is NetworkEpubDownloadResult.NetworkError -> return PdfOpenResult.NetworkError(result.cause)
            }
        }
        val lastPosition = positionStore.load(item.id)
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
    }

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
    // A complete PDF must end with %%EOF. Check the last 32 bytes to allow for trailing whitespace.
    val tailSize = minOf(32L, length()).toInt()
    val tail = ByteArray(tailSize)
    java.io.RandomAccessFile(this, "r").use { raf ->
        raf.seek(length() - tailSize)
        raf.readFully(tail)
    }
    return String(tail).contains("%%EOF")
}
