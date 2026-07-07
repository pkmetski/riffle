package com.riffle.core.data

import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
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
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : PdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        // Resolve the item's OWN server row, not `getActive()`. See EpubRepositoryImpl.openEpub
        // for the rationale — this is the same bug on the PDF side.
        val source = sourceRepository.getById(item.sourceId)
            ?: return PdfOpenResult.NetworkError(IllegalStateException("No server for item"))
        val local = (downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id))?.takeIf { it.isValidPdf() }
        if (local == null) {
            cacheStore.delete(item.sourceId, item.id)
        }
        val pdfFile = if (local != null) {
            local
        } else {
            val token = tokenStorage.getToken(source.id)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No token for server"))
            val ino = item.ebookFileIno ?: run {
                val r = api.getItemEbookFileIno(source.url.value, item.id, token, source.insecureConnectionAllowed)
                if (r is NetworkResult.Success) r.value else return PdfOpenResult.NetworkError(r.errorAsThrowable())
            }
            val result = api.downloadEpub(source.url.value, item.id, ino, token, source.insecureConnectionAllowed)
            if (result !is NetworkResult.Success) return PdfOpenResult.NetworkError(result.errorAsThrowable())
            result.value.use { body -> cacheStore.save(item.sourceId, item.id, body.byteStream()) }
        }
        val activeSource = sourceRepository.getActive()
        val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
    }

    override suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PdfDownloadResult {
        if (downloadsStore.get(item.sourceId, item.id) != null) return PdfDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.sourceId, item.id)
        if (cached != null) {
            val size = cached.length()
            cached.inputStream().use {
                downloadsStore.save(item.sourceId, item.id, ProgressReportingInputStream(it, size, onProgress))
            }
            cacheStore.delete(item.sourceId, item.id)
            return PdfDownloadResult.Success
        }
        // Same rationale as [openPdf]: resolve by item.sourceId so a user-switch (or any second
        // SourceEntity row for the same URL) still fetches from the item's owning server.
        val source = sourceRepository.getById(item.sourceId)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No server for item"))
        val token = tokenStorage.getToken(source.id)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No token for server"))
        val ino = item.ebookFileIno ?: run {
            val r = api.getItemEbookFileIno(source.url.value, item.id, token, source.insecureConnectionAllowed)
            if (r is NetworkResult.Success) r.value else return PdfDownloadResult.NetworkError(r.errorAsThrowable())
        }
        val result = api.downloadEpub(source.url.value, item.id, ino, token, source.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return PdfDownloadResult.NetworkError(result.errorAsThrowable())
        result.value.use { body ->
            val stream = ProgressReportingInputStream(body.byteStream(), body.contentLength(), onProgress)
            downloadsStore.save(item.sourceId, item.id, stream)
        }
        return PdfDownloadResult.Success
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadsStore.delete(sourceId, itemId)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean = downloadsStore.get(sourceId, itemId) != null

    override fun isCached(sourceId: String, itemId: String): Boolean = cacheStore.get(sourceId, itemId) != null

    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        positionStore.save(sourceId, itemId, locatorJson)
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
