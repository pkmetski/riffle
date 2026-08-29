package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CoverGridScaleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CoverGridScaleEntity)

    @Query(
        "SELECT scale FROM cover_grid_scale " +
            "WHERE sourceId = :sourceId AND libraryId = :libraryId AND screenDimensionBucket = :bucket"
    )
    fun observeScale(sourceId: String, libraryId: String, bucket: String): Flow<Float?>
}
