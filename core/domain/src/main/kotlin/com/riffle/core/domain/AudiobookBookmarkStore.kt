package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/** Local-first CRUD for audiobook bookmarks, scoped per (serverId, itemId). */
interface AudiobookBookmarkStore {
    fun observe(serverId: String, itemId: String): Flow<List<AudiobookBookmark>>

    /** Create a bookmark; returns its generated id. [now] is the wall-clock stamp (createdAt + dirty). */
    suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long): String

    suspend fun rename(id: String, title: String, now: Long)

    /** Soft-delete (tombstone) so the deletion can be pushed to ABS, then hard-removed on confirm. */
    suspend fun delete(id: String, now: Long)
}
