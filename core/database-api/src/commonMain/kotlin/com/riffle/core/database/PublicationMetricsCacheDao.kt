package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PublicationMetricsCacheDao {
    @Query(
        "SELECT * FROM publication_metrics_cache " +
            "WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1"
    )
    suspend fun get(sourceId: String, itemId: String): PublicationMetricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PublicationMetricsCacheEntity)
}
