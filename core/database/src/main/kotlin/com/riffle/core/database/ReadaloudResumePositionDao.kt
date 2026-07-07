package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReadaloudResumePositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadaloudResumePositionEntity)

    @Query("SELECT * FROM readaloud_resume_positions WHERE sourceId = :sourceId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(sourceId: String, itemId: String): ReadaloudResumePositionEntity?

    @Query("DELETE FROM readaloud_resume_positions WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun deleteByItemId(sourceId: String, itemId: String)
}
