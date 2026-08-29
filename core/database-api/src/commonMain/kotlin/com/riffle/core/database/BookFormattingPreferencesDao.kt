package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookFormattingPreferencesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookFormattingPreferencesEntity)

    @Query(
        "SELECT * FROM book_formatting_preferences " +
            "WHERE sourceId = :sourceId AND itemId = :itemId " +
            "AND screenDimensionBucket = :screenDimensionBucket LIMIT 1"
    )
    suspend fun getByItemId(
        sourceId: String,
        itemId: String,
        screenDimensionBucket: String,
    ): BookFormattingPreferencesEntity?

    @Query(
        "DELETE FROM book_formatting_preferences " +
            "WHERE sourceId = :sourceId AND itemId = :itemId " +
            "AND screenDimensionBucket = :screenDimensionBucket"
    )
    suspend fun deleteByItemId(
        sourceId: String,
        itemId: String,
        screenDimensionBucket: String,
    )
}
