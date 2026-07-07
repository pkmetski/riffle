package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookBookmarkEntity)

    /** Live, user-visible bookmarks for an item: non-deleted, earliest position first. */
    @Query(
        "SELECT * FROM audiobook_bookmarks WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY positionSec ASC",
    )
    fun observeForItem(sourceId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>>

    /** Live, non-deleted bookmarks across an entire source — for library-wide search. */
    @Query("SELECT * FROM audiobook_bookmarks WHERE sourceId = :sourceId AND deleted = 0 ORDER BY positionSec ASC")
    fun observeForServer(sourceId: String): Flow<List<AudiobookBookmarkEntity>>

    @Query("SELECT * FROM audiobook_bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AudiobookBookmarkEntity?

    /** All rows for an item including tombstones (reconcile needs deletes). */
    @Query("SELECT * FROM audiobook_bookmarks WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun allForItem(sourceId: String, itemId: String): List<AudiobookBookmarkEntity>

    /** Dirty rows for a source (creates, renames, AND tombstoned deletes). */
    @Query("SELECT * FROM audiobook_bookmarks WHERE sourceId = :sourceId AND localUpdatedAt > lastSyncedAt")
    suspend fun dirtyForServer(sourceId: String): List<AudiobookBookmarkEntity>

    @Query("SELECT DISTINCT sourceId FROM audiobook_bookmarks WHERE localUpdatedAt > lastSyncedAt")
    suspend fun serversWithDirtyRows(): List<String>

    /** Live count of unsynced (dirty) rows for an item — drives the "Offline — will sync" note. */
    @Query("SELECT COUNT(*) FROM audiobook_bookmarks WHERE sourceId = :sourceId AND itemId = :itemId AND localUpdatedAt > lastSyncedAt")
    fun observeDirtyCountForItem(sourceId: String, itemId: String): Flow<Int>

    /** Mark clean after a successful push, only if untouched since (compare-and-clear, ADR 0030). */
    @Query(
        "UPDATE audiobook_bookmarks SET lastSyncedAt = :serverStamp, localUpdatedAt = :serverStamp " +
            "WHERE id = :id AND localUpdatedAt = :ifLocalUpdatedAt",
    )
    suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int

    /** Hard-remove a confirmed-deleted tombstone, only if untouched since. */
    @Query("DELETE FROM audiobook_bookmarks WHERE id = :id AND deleted = 1 AND localUpdatedAt = :ifLocalUpdatedAt")
    suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Int

    @Query("DELETE FROM audiobook_bookmarks WHERE id = :id")
    suspend fun hardDelete(id: String)
}
