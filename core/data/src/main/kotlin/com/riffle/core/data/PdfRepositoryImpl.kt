package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import java.io.File

class PdfRepositoryImpl(
    private val catalogRegistry: CatalogRegistry,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val sourceRepository: SourceRepository,
) : PdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        // Resolve the item's OWN source, not the active one. See EpubRepositoryImpl.openEpub
        // for the rationale — this is the same bug on the PDF side.
        val local = (downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id))?.takeIf { it.isValidPdf() }
        if (local == null) {
            cacheStore.delete(item.sourceId, item.id)
        }
        val pdfFile = if (local != null) {
            local
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No catalog for item"))
            try {
                catalog.openFile(item.id, BookFormat.Pdf, handleHint = item.ebookFileIno).use { stream ->
                    cacheStore.save(item.sourceId, item.id, stream.byteStream())
                }
            } catch (t: Throwable) {
                return PdfOpenResult.NetworkError(t)
            }
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
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return try {
            catalog.openFile(item.id, BookFormat.Pdf, handleHint = item.ebookFileIno).use { stream ->
                val progressStream = ProgressReportingInputStream(stream.byteStream(), stream.contentLength, onProgress)
                downloadsStore.save(item.sourceId, item.id, progressStream)
            }
            PdfDownloadResult.Success
        } catch (t: Throwable) {
            PdfDownloadResult.NetworkError(t)
        }
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
