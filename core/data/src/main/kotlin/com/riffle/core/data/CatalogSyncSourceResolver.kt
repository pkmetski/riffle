package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.sync.BookmarkRemote
import com.riffle.core.sync.RemoteBookmark
import com.riffle.core.sync.SyncSource
import com.riffle.core.sync.SyncSourceResolver
import javax.inject.Inject

class CatalogSyncSourceResolver @Inject constructor(
    private val registry: CatalogRegistry,
) : SyncSourceResolver {
    override suspend fun resolve(sourceId: String): SyncSource? {
        val catalog = registry.forSourceId(sourceId) ?: return null
        return object : SyncSource {
            override val supportsEbookProgress = catalog is ProgressPeerCapability
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
