package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Mirrors [PdfRepositoryImpl]. Validates local files by opening their ZIP central directory
 * (not just magic bytes) so truncated downloads are caught before the reader sees them.
 */
class CbzRepositoryImpl(
    private val catalogRegistry: CatalogRegistry,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val sourceRepository: SourceRepository,
    private val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    private val contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
) : CbzRepository {

    override suspend fun openCbz(item: LibraryItem): CbzOpenResult {
        val local = resolveLocalFile(item.sourceId, item.id)
        if (local != null) {
            if (local.tier == LocalFileTier.Cache) contentCacheAccessStore.markAccessed(contentCacheKey(item))
            val activeSource = sourceRepository.getActive()
            val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
            return CbzOpenResult.Success(cbzFile = local.file, lastPosition = lastPosition)
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
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
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
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            return CbzDownloadResult.Success
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return CbzDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return try {
            CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Cbz,
                item.ebookFileIno, downloadsStore, onProgress,
            )
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            CbzDownloadResult.Success
        } catch (t: Throwable) {
            downloadsStore.delete(item.sourceId, item.id)
            CbzDownloadResult.NetworkError(t)
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

    override suspend fun supportsStreaming(sourceId: String): Boolean =
        catalogRegistry.forSourceId(sourceId) as? CbzPageStreamCapability != null

    override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
        val cap = catalogRegistry.forSourceId(sourceId) as? CbzPageStreamCapability
            ?: throw IllegalStateException("No CbzPageStreamCapability for source $sourceId")
        return cap.fetchCbzPageImage(itemId, pageIndex, maxWidth)
    }

    override suspend fun awaitCachedFile(item: LibraryItem): File? {
        val existing = resolveLocalFile(item.sourceId, item.id)
        if (existing != null) {
            if (existing.tier == LocalFileTier.Cache) contentCacheAccessStore.markAccessed(contentCacheKey(item))
            return existing.file
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            CatalogFileTransfer.acquire(
                catalog, item.sourceId, item.id, BookFormat.Cbz,
                item.ebookFileIno, cacheStore,
            ).also {
                contentCacheAccessStore.markAccessed(contentCacheKey(item))
                localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            }
        } catch (_: Throwable) {
            cacheStore.delete(item.sourceId, item.id)
            null
        }
    }

    /**
     * Returns the best available valid local file, preferring a user-pinned download over the
     * background cache. Deletes any file that exists but fails the integrity check — a corrupt
     * or truncated file in either store must not block the streaming fallback path.
     */
    private fun resolveLocalFile(sourceId: String, itemId: String): LocalFile? {
        val dl = downloadsStore.get(sourceId, itemId)
        if (dl != null) {
            if (dl.isValidCbz()) return LocalFile(dl, LocalFileTier.Download)
            downloadsStore.delete(sourceId, itemId)
        }
        val cached = cacheStore.get(sourceId, itemId)
        if (cached != null) {
            if (cached.isValidCbz()) return LocalFile(cached, LocalFileTier.Cache)
            cacheStore.delete(sourceId, itemId)
        }
        return null
    }

    private fun contentCacheKey(item: LibraryItem): ContentCacheKey =
        ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Cbz)
}

private data class LocalFile(val file: File, val tier: LocalFileTier)

private enum class LocalFileTier { Download, Cache }

/**
 * Opens the file as a [ZipFile], which reads the End-of-Central-Directory record from the tail
 * of the file. Truncated or partially-written downloads are missing this record and throw
 * [ZipException] on construction — catching it here prevents the reader from seeing a corrupt
 * file. A magic-byte-only check would pass on truncated downloads (the PK header is always at
 * the start), so we need the full central-directory read.
 *
 * 22 bytes is the minimum valid ZIP (EOCD only, no entries). Performance is fine for large
 * archives: [ZipFile] only reads the central-directory metadata, not file contents.
 */
private fun File.isValidCbz(): Boolean {
    if (!exists() || length() < 22) return false
    return try {
        ZipFile(this).use { true }
    } catch (_: ZipException) {
        false
    } catch (_: IOException) {
        false
    }
}
