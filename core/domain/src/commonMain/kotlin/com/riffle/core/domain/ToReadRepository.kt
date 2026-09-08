package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

const val TO_READ_PLAYLIST_NAME = "To Read"

/**
 * Manages the per-Library, per-User "To Read" Playlist on the active ABS server.
 *
 * Backed by a normal ABS Playlist named [TO_READ_PLAYLIST_NAME], looked up by name and
 * find-or-created on first use. See ADR 0022.
 *
 * Playlists are scoped to (userId, libraryId) on the server, so each ABS account has its
 * own independent To Read list.
 *
 * Cache: in-memory only. Call [refresh] once per library to populate before relying on
 * [observeToReadItemIds] or [isInToRead] — typically from `LibraryItemsViewModel.init`.
 */
interface ToReadRepository {
    /** Item-ids currently in the To Read playlist for [libraryId]. Empty before first refresh. */
    fun observeToReadItemIds(libraryId: String): Flow<Set<String>>

    /** Fetches the To Read playlist from the server and refreshes the in-memory cache. */
    suspend fun refresh(libraryId: String): Boolean

    /**
     * Like [refresh] but targets a specific source rather than the globally-active one.
     * Used by cross-source views (e.g. Riffle) that need to populate the cache for
     * ABS libraries even when a different source is currently active.
     */
    suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean

    suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean

    /**
     * Source-aware variant for cross-source views (e.g. Riffle) where the item's source
     * may differ from the globally-active source. Routes to the library's own backend rather
     * than the active source's backend. Defaults to active-source routing.
     */
    suspend fun isInToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean =
        isInToRead(libraryItemId, libraryId)

    suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean

    /** Source-aware variant — see [isInToReadForSource]. */
    suspend fun addToToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean =
        addToToRead(libraryItemId, libraryId)

    suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean

    /** Source-aware variant — see [isInToReadForSource]. */
    suspend fun removeFromToReadForSource(sourceId: String, libraryItemId: String, libraryId: String): Boolean =
        removeFromToRead(libraryItemId, libraryId)
}
