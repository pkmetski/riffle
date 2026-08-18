package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sync.BookmarkRemote
import com.riffle.core.sync.RemoteBookmark
import com.riffle.core.sync.SyncSource
import com.riffle.core.sync.SyncSourceResolver
import javax.inject.Inject

class CatalogSyncSourceResolver @Inject constructor(
    private val registry: CatalogRegistry,
    private val sourceRepository: SourceRepository,
) : SyncSourceResolver {
    override suspend fun resolve(sourceId: String): SyncSource? {
        val catalog = registry.forSourceId(sourceId) ?: return null
        val source = sourceRepository.getById(sourceId)
        return object : SyncSource {
            // Web sources (Chitanka, Gutenberg) have no ProgressPeerCapability but sync ebook
            // progress via WebDAV (ADR 0062). ProgressSweep gates the ebook reconcile loop on
            // this flag, so web sources must return true here or dirty rows are never processed.
            override val supportsEbookProgress =
                catalog is ProgressPeerCapability || source?.type?.isWebSource == true
            override val supportsAudiobookProgress = catalog is AudiobookProgressPeerCapability
            override val bookmarks = (catalog as? BookmarksCapability)?.let(::CatalogBookmarkRemote)
        }
    }
}

private class CatalogBookmarkRemote(
    private val capability: BookmarksCapability,
) : BookmarkRemote {
    override suspend fun listAll(): List<RemoteBookmark> =
        capability.listAllBookmarks().map(CatalogBookmark::toRemoteBookmark)

    override suspend fun create(itemId: String, timeSec: Int, title: String) {
        capability.createBookmark(itemId, timeSec, title)
    }

    override suspend fun delete(itemId: String, timeSec: Int) {
        capability.deleteBookmark(itemId, timeSec)
    }

    override suspend fun rename(itemId: String, timeSec: Int, title: String) {
        capability.renameBookmark(itemId, timeSec, title)
    }
}

private fun CatalogBookmark.toRemoteBookmark() = RemoteBookmark(
    itemId = itemId,
    timeSec = timeSec,
    title = title,
    createdAt = createdAt,
)
