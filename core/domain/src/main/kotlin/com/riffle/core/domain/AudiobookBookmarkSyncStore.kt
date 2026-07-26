package com.riffle.core.domain

/** Sync-layer storage contract for audiobook bookmarks. Abstracts Room for `core:sync`. */
interface AudiobookBookmarkSyncStore {
    /** All rows including tombstones and dirty entries. */
    suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String): List<SyncableAudiobookBookmark>

    suspend fun upsert(bookmark: SyncableAudiobookBookmark)

    /** Compare-and-clear: mark clean after a successful push. Returns whether the row was touched. */
    suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean

    /** Hard-remove a deleted tombstone if still unchanged. Returns whether the row was removed. */
    suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean

    suspend fun hardDelete(id: String)
}
