package com.riffle.core.data

import kotlinx.coroutines.flow.Flow

/**
 * Local-only "To Read" backing used by [ToReadRepositoryImpl] and [IosToReadRepositoryImpl] when
 * the active Source's Catalog doesn't implement PlaylistsCapability — i.e. every Source without a
 * server-side playlist API (LocalFiles, Chitanka, Gutenberg, RadioEs, OReilly).
 *
 * Persisted per-libraryId. On Android this is a Preferences DataStore; on iOS, NSUserDefaults.
 * [lastUpdateMs] / [setAll] support the WebDAV playlist sweep (last-write-wins, ADR-0063 pattern).
 */
interface LocalToReadStore {
    fun observeItemIds(libraryId: String): Flow<Set<String>>
    suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean
    suspend fun add(libraryId: String, libraryItemId: String)
    suspend fun remove(libraryId: String, libraryItemId: String)

    /** Epoch-ms of the last local write for [libraryId]. Returns 0 if never written. */
    suspend fun lastUpdateMs(libraryId: String): Long

    /** Atomically replace all item IDs and record [lastUpdateMs]. Used by the WebDAV sweep on remote-wins. */
    suspend fun setAll(libraryId: String, itemIds: Set<String>, lastUpdateMs: Long)
}
