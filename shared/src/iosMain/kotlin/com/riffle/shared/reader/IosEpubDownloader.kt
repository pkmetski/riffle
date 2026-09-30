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
 * ABS items (ebookFileIno != null) stream directly through [AbsFileStreamer] using the ABS
 * `/api/items/{id}/file/{ino}` endpoint. All other sources (Komga, Kavita, WebDAV, …) stream
 * through [CatalogRegistry.forSourceId] → [Catalog.withFileStream].
 *
 * Both paths use [IosItemFiles.writeChannel] which calls mkdirsForFile() first, so the
 * $sourceId/ subdirectory is always created before the write (NSFileManager.createFileAtPath
 * silently fails when the parent directory does not exist).
 */
class IosEpubDownloader(
    private val absStreamer: AbsFileStreamer,
    private val catalogRegistry: CatalogRegistry,
    private val fileStore: FileStore,
) {
    suspend fun localPath(item: LibraryItem): String? {
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) return downloadPath

        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) return cachePath

        return if (item.ebookFileIno != null) {
            absStreamer.withStream(item) { channel, length ->
                if (IosItemFiles.writeChannel(cachePath, channel, length)) cachePath else null
            }
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
            try {
                catalog.withFileStream(item.id, BookFormat.Epub, null) { stream ->
                    if (IosItemFiles.writeChannel(cachePath, stream.channel, stream.contentLength)) cachePath else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
