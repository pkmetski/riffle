package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.LocalAvailabilityEvents
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
    private val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    private val contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
) : PdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        // Resolve the item's OWN source, not the active one. See EpubRepositoryImpl.openEpub
        // for the rationale — this is the same bug on the PDF side.
        val downloaded = downloadsStore.get(item.sourceId, item.id)?.takeIf { it.isValidPdf() }
        val cached = if (downloaded == null) cacheStore.get(item.sourceId, item.id)?.takeIf { it.isValidPdf() } else null
        if (downloaded == null && cached == null) {
            cacheStore.delete(item.sourceId, item.id)
        }
        val pdfFile = if (downloaded != null) {
            downloaded
        } else if (cached != null) {
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            cached
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No catalog for item"))
            try {
                CatalogFileTransfer.acquire(
                    catalog, item.sourceId, item.id, BookFormat.Pdf,
                    item.ebookFileIno, cacheStore,
                ).also {
                    contentCacheAccessStore.markAccessed(contentCacheKey(item))
                    localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
                }
            } catch (t: Throwable) {
                return PdfOpenResult.NetworkError(t)
            }
        }
        val activeSource = sourceRepository.getActive()
        val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
    }

    override suspend fun openPdfForMetadata(item: LibraryItem): PdfOpenResult {
        val local = (downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id))?.takeIf { it.isValidPdf() }
        if (local == null) {
            cacheStore.delete(item.sourceId, item.id)
        }
        val (pdfFile, temporary) = if (local != null) {
            local to false
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId)
                ?: return PdfOpenResult.NetworkError(IllegalStateException("No catalog for item"))
            try {
                CatalogFileTransfer.acquireTemporary(
                    catalog,
                    item.id,
                    BookFormat.Pdf,
                    item.ebookFileIno,
                    ".pdf",
                ) to true
            } catch (t: Throwable) {
                return PdfOpenResult.NetworkError(t)
            }
        }
        return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = null, temporary = temporary)
    }

    override suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PdfDownloadResult {
        if (downloadsStore.get(item.sourceId, item.id) != null) return PdfDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.sourceId, item.id)
        if (cached != null) {
            CatalogFileTransfer.promote(
                item.sourceId, item.id, cached, cacheStore, downloadsStore, onProgress,
            )
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            return PdfDownloadResult.Success
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return try {
            CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Pdf,
                item.ebookFileIno, downloadsStore, onProgress,
            )
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            PdfDownloadResult.Success
        } catch (t: Throwable) {
            PdfDownloadResult.NetworkError(t)
        }
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadsStore.delete(sourceId, itemId)
        cacheStore.delete(sourceId, itemId)
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean = downloadsStore.get(sourceId, itemId) != null

    override fun isCached(sourceId: String, itemId: String): Boolean = cacheStore.get(sourceId, itemId) != null

    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        positionStore.save(sourceId, itemId, locatorJson)
    }

    private fun contentCacheKey(item: LibraryItem): ContentCacheKey =
        ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Pdf)
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
