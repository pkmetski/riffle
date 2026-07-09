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
        "UPDATE local_files_files SET lastSeenAtEpochMs = :seenAt, folderTreeUri = :folderTreeUri " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId",
    )
    suspend fun touchLastSeen(sourceId: String, sourceItemId: String, folderTreeUri: String, seenAt: Long)

    /**
     * Rows in this source that were not seen during the current scan. Callers hard-delete these
     * (and their library_items rows + copied bytes) after the walk finishes.
     */
    @Query("SELECT * FROM local_files_files WHERE sourceId = :sourceId AND lastSeenAtEpochMs < :scanStart")
    suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileEntity>

    @Query("DELETE FROM local_files_files WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId")
    suspend fun delete(sourceId: String, sourceItemId: String)
}
