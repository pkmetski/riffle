package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey

/**
 * Shared downloads → cache fallback lookup for EPUB files.
 *
 * Checks the permanent download namespace first; if absent, checks the evictable cache
 * namespace and calls [ContentCacheAccessStore.markAccessed] so the [ContentCacheCleaner]
 * uses the last-read time rather than the file-creation time for eviction decisions.
 *
 * Returns the absolute path when found locally, null when neither store holds the file.
 * The caller is responsible for fetching from the network on a null return.
 */
class EpubLocalPathResolver(
    private val fileStore: FileStore,
    private val contentCacheAccessStore: ContentCacheAccessStore,
) {
    suspend fun resolve(sourceId: String, itemId: String): String? {
        val relativePath = "$sourceId/$itemId.epub"
        if (fileStore.exists(NS_EPUB_DOWNLOADS, relativePath)) {
            return fileStore.resolve(NS_EPUB_DOWNLOADS, relativePath)
        }
        if (fileStore.exists(NS_EPUB_CACHE, relativePath)) {
            contentCacheAccessStore.markAccessed(ContentCacheKey(sourceId, itemId, ContentCacheArtifactKind.Epub))
            return fileStore.resolve(NS_EPUB_CACHE, relativePath)
        }
        return null
    }
}
