package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TocCacheDao {
    @Query("SELECT * FROM toc_cache WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun get(sourceId: String, itemId: String): TocCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TocCacheEntity)
}
