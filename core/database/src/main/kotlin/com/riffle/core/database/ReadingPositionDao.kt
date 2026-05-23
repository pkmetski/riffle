package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadingPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE itemId = :itemId LIMIT 1")
    suspend fun getByItemId(itemId: String): ReadingPositionEntity?

    @Query("UPDATE reading_positions SET localUpdatedAt = :millis WHERE itemId = :itemId")
    suspend fun updateLocalTimestamp(itemId: String, millis: Long)
}
