package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TocCacheDao {
    @Query("SELECT * FROM toc_cache WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun get(serverId: String, itemId: String): TocCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TocCacheEntity)
}
