package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryItemDao {

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId ORDER BY title ASC")
    fun observeByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query("DELETE FROM library_items WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)
}
