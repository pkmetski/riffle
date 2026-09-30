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
 * Priority: permanent download (epub-downloads) → cached copy (epub-cache) → fetch.
 *
 * All sources (ABS, Komga, Kavita, WebDAV, …) stream through [CatalogRegistry.forSourceId] →
 * [Catalog.withFileStream]. For ABS items, [AbsCommonCatalog] resolves the file inode from
 * the `/api/items/{id}` endpoint on demand (the library-list API does not include it).
 *
 * [IosItemFiles.writeChannel] calls mkdirsForFile() first, so the $sourceId/ subdirectory is
 * always created before the write.
 */
class IosEpubDownloader(
    private val catalogRegistry: CatalogRegistry,
    private val fileStore: FileStore,
) {
    suspend fun localPath(item: LibraryItem): String? {
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) {
            return downloadPath
        }

        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) {
            return cachePath
        }

        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
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
