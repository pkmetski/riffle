package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.IosEpubPaths
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager

/**
 * Returns a local path for the given EPUB, used by the reader to open the file.
 * Priority: permanent download (epub-downloads) → cached copy (epub-cache) → fetch via catalog.
 *
 * Fetches through [CatalogRegistry] → [Catalog.withFileStream] so every source (ABS, Komga,
 * Kavita, Chitanka, WebDAV, …) is supported without ABS-specific URL construction. The ABS
 * catalog uses [LibraryItem.ebookFileIno] as a download-handle hint when present; other catalogs
 * ignore it. Previously this class bypassed the catalog layer and called ABS directly, which meant
 * any book from a non-ABS source returned null here and showed "Could not download book".
 */
class IosEpubDownloader(
    private val catalogRegistry: CatalogRegistry,
    private val fileStore: FileStore,
) {
    suspend fun localPath(item: LibraryItem): String? {
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) return downloadPath

        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) return cachePath

        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
                if (IosItemFiles.writeChannel(cachePath, stream.channel, stream.contentLength)) cachePath else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
