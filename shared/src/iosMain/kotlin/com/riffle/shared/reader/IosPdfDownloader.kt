package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_PDF_CACHE
import com.riffle.core.data.NS_PDF_DOWNLOADS
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.IosPdfPaths
import com.riffle.core.domain.StoredItemRef
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.posix.memcpy

/**
 * Returns a local path for the given PDF, used by the reader to open the file.
 * Priority: permanent download (pdf-downloads) → cached copy (pdf-cache) → fetch.
 *
 * Validates %PDF header and %%EOF tail on every local file before serving, mirroring
 * [PdfRepositoryImpl.isValidPdf]. Corrupt or truncated files are deleted so the next call
 * re-downloads rather than handing a broken file to PDFKit on every open.
 *
 * Cache bookkeeping mirrors Android's [PdfRepositoryImpl.openPdf]:
 * - [ContentCacheAccessStore.markAccessed] is called when a cached copy is served.
 * - [LocalAvailabilityEvents.notifyChanged] is called after a fresh network fetch.
 *
 * All sources (ABS, Komga, Kavita, WebDAV, …) stream through [CatalogRegistry.forSourceId] →
 * [Catalog.withFileStream]. Mirrors [IosEpubDownloader].
 *
 * [IosItemFiles.writeChannel] calls mkdirsForFile() first, so the $sourceId/ subdirectory is
 * always created before the write.
 */
class IosPdfDownloader(
    private val catalogRegistry: CatalogRegistry,
    private val fileStore: FileStore,
    private val contentCacheAccessStore: ContentCacheAccessStore = object : ContentCacheAccessStore {
        override suspend fun markAccessed(key: ContentCacheKey) {}
        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {}
        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> = emptyMap()
        override suspend fun forget(key: ContentCacheKey) {}
    },
    private val localAvailabilityEvents: LocalAvailabilityEvents = object : LocalAvailabilityEvents {
        override val changes = MutableSharedFlow<StoredItemRef>()
        override fun notifyChanged(sourceId: String, itemId: String) {}
    },
) {
    @OptIn(ExperimentalForeignApi::class)
    suspend fun localPath(item: LibraryItem): String? {
        val downloadPath = fileStore.resolve(NS_PDF_DOWNLOADS, IosPdfPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) {
            if (isValidPdf(downloadPath)) return downloadPath
            NSFileManager.defaultManager.removeItemAtPath(downloadPath, error = null)
        }

        val cachePath = fileStore.resolve(NS_PDF_CACHE, IosPdfPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) {
            if (isValidPdf(cachePath)) {
                contentCacheAccessStore.markAccessed(ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Pdf))
                return cachePath
            }
            NSFileManager.defaultManager.removeItemAtPath(cachePath, error = null)
        }

        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            catalog.withFileStream(item.id, BookFormat.Pdf, item.ebookFileIno) { stream ->
                val written = IosItemFiles.writeChannel(cachePath, stream.channel, stream.contentLength)
                if (written) {
                    contentCacheAccessStore.markAccessed(ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Pdf))
                    localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
                    cachePath
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Returns true when [path] is a syntactically valid PDF file: starts with the %PDF magic bytes
 * and ends with an %%EOF marker in the last 32 bytes. Mirrors [PdfRepositoryImpl.isValidPdf].
 *
 * Uses [NSFileHandle] to read only the header and tail so large PDFs are not loaded into memory.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun isValidPdf(path: String): Boolean {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return false
    val fileSize = handle.seekToEndOfFile().toLong()
    if (fileSize < 8) { handle.closeFile(); return false }

    // Check %PDF header
    handle.seekToFileOffset(0u)
    val headerData = handle.readDataOfLength(4u)
    if (headerData.length < 4u) { handle.closeFile(); return false }
    val headerBytes = ByteArray(4)
    headerBytes.usePinned { pinned -> memcpy(pinned.addressOf(0), headerData.bytes, 4u) }
    if (!headerBytes.contentEquals("%PDF".encodeToByteArray())) { handle.closeFile(); return false }

    // Check %%EOF tail
    val tailSize = minOf(32L, fileSize)
    handle.seekToFileOffset((fileSize - tailSize).toULong())
    val tailData = handle.readDataOfLength(tailSize.toULong())
    handle.closeFile()
    if (tailData.length.toLong() < tailSize) return false
    val tailBytes = ByteArray(tailSize.toInt())
    tailBytes.usePinned { pinned -> memcpy(pinned.addressOf(0), tailData.bytes, tailSize.toULong()) }
    return tailBytes.decodeToString().contains("%%EOF")
}
