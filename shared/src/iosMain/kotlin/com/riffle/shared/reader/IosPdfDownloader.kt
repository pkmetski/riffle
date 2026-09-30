package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_PDF_CACHE
import com.riffle.core.data.NS_PDF_DOWNLOADS
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.IosPdfPaths
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager

/**
 * Returns a local path for the given PDF, used by the reader to open the file.
 * Priority: permanent download (pdf-downloads) → cached copy (pdf-cache) → fetch.
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
) {
    suspend fun localPath(item: LibraryItem): String? {
        val downloadPath = fileStore.resolve(NS_PDF_DOWNLOADS, IosPdfPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) {
            return downloadPath
        }

        val cachePath = fileStore.resolve(NS_PDF_CACHE, IosPdfPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) {
            return cachePath
        }

        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            catalog.withFileStream(item.id, BookFormat.Pdf, item.ebookFileIno) { stream ->
                val written = IosItemFiles.writeChannel(cachePath, stream.channel, stream.contentLength)
                if (written) cachePath else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
