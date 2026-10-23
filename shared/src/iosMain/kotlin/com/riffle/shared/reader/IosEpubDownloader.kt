package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.EpubLocalPathResolver
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.IosEpubPaths
import kotlinx.coroutines.CancellationException

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
    private val contentCacheAccessStore: ContentCacheAccessStore,
) {
    private val localPathResolver = EpubLocalPathResolver(fileStore, contentCacheAccessStore)

    suspend fun localPath(item: LibraryItem): String? {
        val localPath = localPathResolver.resolve(item.sourceId, item.id)
        if (localPath != null) return localPath

        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(item.sourceId, item.id))
        return try {
            catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
                val written = IosItemFiles.writeChannel(cachePath, stream.channel, stream.contentLength)
                if (written) {
                    contentCacheAccessStore.markAccessed(ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Epub))
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
