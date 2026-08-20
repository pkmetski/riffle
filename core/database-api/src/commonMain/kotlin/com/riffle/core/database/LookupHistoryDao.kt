package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LookupHistoryDao {

    @Query(
        "SELECT form FROM lookup_history " +
            "WHERE languageTag = :languageTag " +
            "ORDER BY lookedUpAt DESC LIMIT :limit"
    )
    fun observeRecent(languageTag: String, limit: Int): Flow<List<String>>

    @Insert
    suspend fun insert(entity: LookupHistoryEntity)

    @Query(
        "DELETE FROM lookup_history " +
            "WHERE languageTag = :languageTag AND id NOT IN (" +
            "  SELECT id FROM lookup_history " +
            "  WHERE languageTag = :languageTag " +
            "  ORDER BY lookedUpAt DESC LIMIT 50" +
            ")"
    )
    suspend fun pruneOldest(languageTag: String)
}
