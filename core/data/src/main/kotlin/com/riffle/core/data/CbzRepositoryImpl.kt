package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import java.io.File

/**
 * Mirrors [PdfRepositoryImpl]. CBZ is a plain ZIP-of-images so we validate by ZIP-signature only;
 * detailed structural validation happens when the reader opens the archive via `CbzArchive`.
 */
class CbzRepositoryImpl(
    private val catalogRegistry: CatalogRegistry,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val sourceRepository: SourceRepository,
) : CbzRepository {

    override suspend fun openCbz(item: LibraryItem): CbzOpenResult {
        val local = (downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id))?.takeIf { it.isValidCbz() }
        if (local == null) {
            cacheStore.delete(item.sourceId, item.id)
        }
        if (local != null) {
            val activeSource = sourceRepository.getActive()
            val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
            return CbzOpenResult.Success(cbzFile = local, lastPosition = lastPosition)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return CbzOpenResult.NetworkError(IllegalStateException("No catalog for item"))
        val streamCap = catalog as? CbzPageStreamCapability
        if (streamCap != null) {
            val pageCount = try {
                streamCap.fetchCbzPageCount(item.id)
            } catch (t: Throwable) {
                return CbzOpenResult.NetworkError(t)
            }
            if (pageCount <= 0) return CbzOpenResult.NetworkError(
                IllegalStateException("Server returned zero page count for ${item.id}")
            )
            val activeSource = sourceRepository.getActive()
            val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
            return CbzOpenResult.Streaming(pageCount = pageCount, lastPosition = lastPosition)
        }
        return try {
            val cbzFile = CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Cbz,
                item.ebookFileIno, cacheStore,
            )
            val activeSource = sourceRepository.getActive()
            val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
            CbzOpenResult.Success(cbzFile = cbzFile, lastPosition = lastPosition)
        } catch (t: Throwable) {
            CbzOpenResult.NetworkError(t)
        }
    }

    override suspend fun downloadCbz(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): CbzDownloadResult {
        if (downloadsStore.get(item.sourceId, item.id) != null) return CbzDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.sourceId, item.id)
        if (cached != null) {
            CatalogFileTransfer.promote(
                item.sourceId, item.id, cached, cacheStore, downloadsStore, onProgress,
            )
            return CbzDownloadResult.Success
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return CbzDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return try {
            CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Cbz,
                item.ebookFileIno, downloadsStore, onProgress,
            )
            CbzDownloadResult.Success
        } catch (t: Throwable) {
            CbzDownloadResult.NetworkError(t)
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

    override suspend fun supportsStreaming(sourceId: String): Boolean =
        catalogRegistry.forSourceId(sourceId) as? CbzPageStreamCapability != null

    override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int): ByteArray {
        val cap = catalogRegistry.forSourceId(sourceId) as? CbzPageStreamCapability
            ?: throw IllegalStateException("No CbzPageStreamCapability for source $sourceId")
        return cap.fetchCbzPageImage(itemId, pageIndex)
    }

    override suspend fun awaitCachedFile(item: LibraryItem): File? {
        val existing = (downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id))
            ?.takeIf { it.isValidCbz() }
        if (existing != null) return existing
        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Cbz,
                item.ebookFileIno, cacheStore,
            )
        } catch (_: Throwable) {
            null
        }
    }
}

private fun File.isValidCbz(): Boolean {
    if (!exists() || length() < 4) return false
    return inputStream().use { stream ->
        val header = ByteArray(4).also { stream.read(it) }
        // "PK\x03\x04" — ZIP local file header. Enough to reject truncation/garbage before the
        // reader opens the archive; downstream `CbzArchive` filters non-image entries.
        header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }
}
