package com.riffle.shared.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_PDF_CACHE
import com.riffle.core.data.NS_PDF_DOWNLOADS
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager

/** Path scheme for locally stored PDFs, mirroring [IosEpubPaths]. */
internal object IosPdfPaths {
    fun downloadRelativePath(sourceId: String, itemId: String) = IosItemFiles.relativePath(sourceId, itemId, ".pdf")
    fun cacheRelativePath(sourceId: String, itemId: String) = IosItemFiles.relativePath(sourceId, itemId, ".pdf")
}

/**
 * iOS [PdfRepository]. Downloads are routed through [CatalogRegistry.forSourceId] →
 * [com.riffle.core.catalog.Catalog.withFileStream], which works for every registered source type
 * (ABS, Komga, …) instead of the previous ABS-only URL construction. Mirrors
 * [IosEpubRepositoryImpl].
 *
 * [IosItemFiles.writeChannel] calls mkdirsForFile() first, so the $sourceId/ subdirectory is
 * always created before the write.
 */
internal class IosPdfRepositoryImpl(
    private val positionStore: ReadingPositionStore,
    private val fileStore: FileStore,
    private val catalogRegistry: CatalogRegistry,
) : PdfRepository {

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {
        positionStore.save(sourceId, itemId, locatorJson)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(downloadPath(sourceId, itemId))

    override fun isCached(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(cachePath(sourceId, itemId))

    override suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PdfDownloadResult {
        if (isDownloaded(item.sourceId, item.id)) return PdfDownloadResult.AlreadyDownloaded

        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No catalog for source ${item.sourceId}"))

        val destPath = downloadPath(item.sourceId, item.id)
        return try {
            catalog.withFileStream(item.id, BookFormat.Pdf, item.ebookFileIno) { stream ->
                if (IosItemFiles.writeChannel(destPath, stream.channel, stream.contentLength, onProgress)) {
                    PdfDownloadResult.Success
                } else {
                    PdfDownloadResult.NetworkError(IllegalStateException("Failed to write PDF to $destPath"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PdfDownloadResult.NetworkError(e)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun removeDownload(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(downloadPath(sourceId, itemId), null)
        NSFileManager.defaultManager.removeItemAtPath(cachePath(sourceId, itemId), null)
    }

    /** Local path of an already-stored PDF (download preferred over cache), or null when absent. */
    fun localPath(sourceId: String, itemId: String): String? {
        val download = downloadPath(sourceId, itemId)
        if (NSFileManager.defaultManager.fileExistsAtPath(download)) return download
        val cache = cachePath(sourceId, itemId)
        return if (NSFileManager.defaultManager.fileExistsAtPath(cache)) cache else null
    }

    private fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_PDF_DOWNLOADS, IosPdfPaths.downloadRelativePath(sourceId, itemId))

    private fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_PDF_CACHE, IosPdfPaths.cacheRelativePath(sourceId, itemId))
}
