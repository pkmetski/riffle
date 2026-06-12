package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadingPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(serverId: String, itemId: String): ReadingPositionEntity?

    @Query("UPDATE reading_positions SET localUpdatedAt = :millis WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long)

    // Compare-and-clear conditional writes (ADR 0030). Each is a single atomic UPDATE guarded by
    // `localUpdatedAt = :ifLocalUpdatedAt`, so a concurrent local save() landing mid-flight makes the
    // write a no-op (0 rows) instead of clobbering the fresh edit. Returns rows affected.

    /** Server wins: overwrite the position and set both stamps clean (= server stamp). */
    @Query(
        "UPDATE reading_positions SET cfi = :position, localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun acceptServerIfUnchanged(
        serverId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** Local push confirmed: adopt the server-returned stamp into both timestamps (clean). */
    @Query(
        "UPDATE reading_positions SET localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmPushedIfUnchanged(
        serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** Already in sync: clear dirty by lifting lastSyncedAt to localUpdatedAt. */
    @Query(
        "UPDATE reading_positions SET lastSyncedAt = localUpdatedAt " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmInSyncIfUnchanged(serverId: String, itemId: String, ifLocalUpdatedAt: Long): Int

    /** Dirty rows for one server (ADR 0030 sweep): localUpdatedAt strictly ahead of lastSyncedAt. */
    @Query("SELECT * FROM reading_positions WHERE serverId = :serverId AND localUpdatedAt > lastSyncedAt")
    suspend fun dirtyForServer(serverId: String): List<ReadingPositionEntity>

    /** All distinct serverIds that have at least one dirty row. */
    @Query("SELECT DISTINCT serverId FROM reading_positions WHERE localUpdatedAt > lastSyncedAt")
    suspend fun serversWithDirtyRows(): List<String>
}
