package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    /** Live, non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Live, non-deleted annotations across every item for a server, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForServer(serverId: String): Flow<List<AnnotationEntity>>

    /** One-shot read of non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity>

    /** One-shot read of every row for an item, **including tombstones** (deleted = 1). Used by
     *  AnnotationSyncController.pushPending so tombstones can propagate to other devices. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getAllForItemIncludingDeleted(serverId: String, itemId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnotationEntity?

    /** One-shot lookup of the live (non-deleted) annotation matching this exact CFI in this item.
     *  Used at open-from-library to bind openAtCfi back to its source annotation so continuous mode
     *  can scroll to the `<mark data-riffle-ann="<id>">` decoration once it's been applied. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND cfi = :cfi " +
            "AND deleted = 0 LIMIT 1"
    )
    suspend fun getByItemAndCfi(serverId: String, itemId: String, cfi: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnnotationEntity)

    @Upsert
    suspend fun upsertAll(annotations: List<AnnotationEntity>)

    /** Tombstone an annotation so the delete can later propagate to other devices. */
    @Query("UPDATE annotations SET deleted = 1, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun tombstone(id: String, updatedAt: Long, deviceId: String)

    /** Recolour an annotation in place, bumping updatedAt + provenance so the change can propagate. */
    @Query("UPDATE annotations SET color = :color, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String)

    /** Set (or clear) the note on a highlight in place, bumping updatedAt + provenance. */
    @Query("UPDATE annotations SET note = :note, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String)

    /** Live, non-deleted annotations for an item sorted by reading position (spine order, then within-chapter). */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY spineIndex ASC, progression ASC"
    )
    fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Update the user-editable title of a bookmark, bumping updatedAt + provenance. */
    @Query("UPDATE annotations SET bookmarkTitle = :title, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id AND type = 'BOOKMARK'")
    suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String)

    /** Pending-row count for one book — live Flow for reader-chrome and per-book status. */
    @Query("SELECT COUNT(*) FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND updatedAt > lastSyncedAt")
    fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int>

    /** Pending-row count across every book — live Flow for the Settings list-row badge. */
    @Query("SELECT COUNT(*) FROM annotations WHERE updatedAt > lastSyncedAt")
    fun observePendingCountAcrossAll(): Flow<Int>

    /** One row per `(serverId, itemId)` with at least one dirty annotation. Used by AnnotationSweep. */
    @Query("SELECT DISTINCT serverId, itemId FROM annotations WHERE updatedAt > lastSyncedAt")
    suspend fun dirtyServerItems(): List<DirtyServerItem>

    /** Stamp the given row ids as synced at the given wall-clock timestamp. */
    @Query("UPDATE annotations SET lastSyncedAt = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    /** Result row for [dirtyServerItems]. */
    data class DirtyServerItem(val serverId: String, val itemId: String)
}
