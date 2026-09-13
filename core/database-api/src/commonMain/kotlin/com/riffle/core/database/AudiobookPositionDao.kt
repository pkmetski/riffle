package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudiobookPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookPositionEntity)

    @Query("SELECT * FROM audiobook_positions WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity?

    // Compare-and-clear conditional writes (ADR 0036) — see ReadingPositionDao for the rationale.

    /** Server wins: overwrite the seconds and set both stamps clean (= server stamp). */
    @Query(
        "UPDATE audiobook_positions SET positionSec = :positionSec, deleted = :deleted, localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun acceptServerIfUnchanged(
        sourceId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean,
    ): Int

    /** Local push confirmed: adopt the server-returned stamp into both timestamps (clean). */
    @Query(
        "UPDATE audiobook_positions SET localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmPushedIfUnchanged(
        sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** Already in sync: clear dirty by lifting lastSyncedAt to localUpdatedAt. */
    @Query(
        "UPDATE audiobook_positions SET lastSyncedAt = localUpdatedAt " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Int

    /** Dirty rows for one source (ADR 0036 sweep). */
    @Query("SELECT * FROM audiobook_positions WHERE sourceId = :sourceId AND localUpdatedAt > lastSyncedAt")
    suspend fun dirtyForSource(sourceId: String): List<AudiobookPositionEntity>

    /** All distinct sourceIds that have at least one dirty row. */
    @Query("SELECT DISTINCT sourceId FROM audiobook_positions WHERE localUpdatedAt > lastSyncedAt")
    suspend fun sourcesWithDirtyRows(): List<String>

    /** Mark the row as a soft-delete tombstone and make it dirty so the deletion propagates to WebDAV. */
    @Query(
        "UPDATE audiobook_positions SET deleted = 1, localUpdatedAt = :localUpdatedAt " +
            "WHERE sourceId = :sourceId AND itemId = :itemId"
    )
    suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long)

    /** Server wins and the remote position was a deletion: mark local row as deleted and clean. */
    @Query(
        "UPDATE audiobook_positions SET deleted = 1, localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun acceptServerDeletionIfUnchanged(
        sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** All rows for a source — used by RemoteProgressIndex to reconcile clean rows against WebDAV. */
    @Query("SELECT * FROM audiobook_positions WHERE sourceId = :sourceId")
    suspend fun allForSource(sourceId: String): List<AudiobookPositionEntity>
}
