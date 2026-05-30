package com.riffle.core.data

import kotlinx.coroutines.flow.Flow

const val TO_READ_PLAYLIST_NAME = "To Read"

/**
 * Manages the per-Library, per-User "To Read" Playlist on the active ABS server.
 *
 * Backed by a normal ABS Playlist named [TO_READ_PLAYLIST_NAME], looked up by name and
 * find-or-created on first use. See ADR 0019.
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

    suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean
    suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean
    suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean
}
