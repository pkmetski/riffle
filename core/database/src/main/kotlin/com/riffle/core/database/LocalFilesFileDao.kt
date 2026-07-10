package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalFilesFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFilesFileEntity)

    @Query("SELECT * FROM local_files_files WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId LIMIT 1")
    suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity?

    @Query("SELECT * FROM local_files_files WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: String): List<LocalFilesFileEntity>

    @Query(
        "UPDATE local_files_files SET lastSeenAtEpochMs = :seenAt " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId",
    )
    suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long)

    @Query("DELETE FROM local_files_files WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId")
    suspend fun delete(sourceId: String, sourceItemId: String)
}
