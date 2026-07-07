package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/** Local-first CRUD for audiobook bookmarks, scoped per (sourceId, itemId). */
interface AudiobookBookmarkStore {
    fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>>

    /** Live stream of all non-deleted bookmarks for a server — for library-wide search. */
    fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>>

    /** Live: whether this item has unsynced (dirty) bookmarks pending a push to ABS. */
    fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean>

    /** Create a bookmark; returns its generated id. [now] is the wall-clock stamp (createdAt + dirty). */
    suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String

    suspend fun rename(id: String, title: String, now: Long)

    /** Soft-delete (tombstone) so the deletion can be pushed to ABS, then hard-removed on confirm. */
    suspend fun delete(id: String, now: Long)
}
