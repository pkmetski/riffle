package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFilesFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFilesFolderEntity)

    @Query("SELECT * FROM local_files_folders WHERE sourceId = :sourceId ORDER BY addedAtEpochMs ASC")
    suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity>

    @Query("SELECT * FROM local_files_folders WHERE sourceId = :sourceId ORDER BY addedAtEpochMs ASC")
    fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>>

    @Query("DELETE FROM local_files_folders WHERE sourceId = :sourceId AND treeUri = :treeUri")
    suspend fun delete(sourceId: String, treeUri: String)
}
