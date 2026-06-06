package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadaloudResumePositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadaloudResumePositionEntity)

    @Query("SELECT * FROM readaloud_resume_positions WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(serverId: String, itemId: String): ReadaloudResumePositionEntity?
}
