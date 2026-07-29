package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFileMetadataOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFileMetadataOverrideEntity)

    @Query(
        "SELECT * FROM local_file_metadata_overrides " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId LIMIT 1",
    )
    fun observe(sourceId: String, sourceItemId: String): Flow<LocalFileMetadataOverrideEntity?>

    @Query(
        "SELECT * FROM local_file_metadata_overrides " +
            "WHERE sourceId = :sourceId AND sourceItemId IN (:sourceItemIds)",
    )
    suspend fun getForItems(
        sourceId: String,
        sourceItemIds: List<String>,
    ): List<LocalFileMetadataOverrideEntity>

    @Query(
        "SELECT * FROM local_file_metadata_overrides " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId LIMIT 1",
    )
    suspend fun getForItem(sourceId: String, sourceItemId: String): LocalFileMetadataOverrideEntity?

    @Query(
        "DELETE FROM local_file_metadata_overrides " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId",
    )
    suspend fun delete(sourceId: String, sourceItemId: String)
}
