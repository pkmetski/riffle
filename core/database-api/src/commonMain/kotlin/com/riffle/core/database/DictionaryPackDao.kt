package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryPackDao {

    @Query("SELECT * FROM dictionary_packs WHERE languageTag = :languageTag LIMIT 1")
    fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?>

    @Query("SELECT * FROM dictionary_packs")
    fun observeAll(): Flow<List<DictionaryPackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DictionaryPackEntity)

    @Query("UPDATE dictionary_packs SET state = :state WHERE languageTag = :languageTag")
    suspend fun updateState(languageTag: String, state: String)

    @Query("DELETE FROM dictionary_packs WHERE languageTag = :languageTag")
    suspend fun delete(languageTag: String)
}
