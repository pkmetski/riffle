package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookFormattingPreferencesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookFormattingPreferencesEntity)

    @Query("SELECT * FROM book_formatting_preferences WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun getByItemId(serverId: String, itemId: String): BookFormattingPreferencesEntity?

    @Query("DELETE FROM book_formatting_preferences WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteByItemId(serverId: String, itemId: String)
}
