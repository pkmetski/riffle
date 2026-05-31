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
}
