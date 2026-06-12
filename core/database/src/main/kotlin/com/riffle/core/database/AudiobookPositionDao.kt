package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudiobookPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudiobookPositionEntity)

    @Query("SELECT * FROM audiobook_positions WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(serverId: String, itemId: String): AudiobookPositionEntity?

    // Compare-and-clear conditional writes (ADR 0030) — see ReadingPositionDao for the rationale.

    /** Server wins: overwrite the seconds and set both stamps clean (= server stamp). */
    @Query(
        "UPDATE audiobook_positions SET positionSec = :positionSec, localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun acceptServerIfUnchanged(
        serverId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** Local push confirmed: adopt the server-returned stamp into both timestamps (clean). */
    @Query(
        "UPDATE audiobook_positions SET localUpdatedAt = :serverStamp, lastSyncedAt = :serverStamp " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmPushedIfUnchanged(
        serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Int

    /** Already in sync: clear dirty by lifting lastSyncedAt to localUpdatedAt. */
    @Query(
        "UPDATE audiobook_positions SET lastSyncedAt = localUpdatedAt " +
            "WHERE serverId = :serverId AND itemId = :itemId AND localUpdatedAt = :ifLocalUpdatedAt"
    )
    suspend fun confirmInSyncIfUnchanged(serverId: String, itemId: String, ifLocalUpdatedAt: Long): Int

    /** Dirty rows for one server (ADR 0030 sweep). */
    @Query("SELECT * FROM audiobook_positions WHERE serverId = :serverId AND localUpdatedAt > lastSyncedAt")
    suspend fun dirtyForServer(serverId: String): List<AudiobookPositionEntity>

    /** All distinct serverIds that have at least one dirty row. */
    @Query("SELECT DISTINCT serverId FROM audiobook_positions WHERE localUpdatedAt > lastSyncedAt")
    suspend fun serversWithDirtyRows(): List<String>
}
