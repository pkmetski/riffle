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

    @Query("""
        SELECT * FROM library_items
        WHERE libraryId = :libraryId
        AND id NOT IN (
            SELECT itemId FROM series_items
            WHERE seriesId IN (SELECT id FROM series WHERE libraryId = :libraryId)
        )
        AND id NOT IN (
            SELECT itemId FROM collection_items
            WHERE collectionId IN (SELECT id FROM collections WHERE libraryId = :libraryId)
        )
        ORDER BY title ASC
    """)
    fun observeUngroupedByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query("SELECT * FROM library_items WHERE id = :itemId LIMIT 1")
    suspend fun getById(itemId: String): LibraryItemEntity?

    @Query("DELETE FROM library_items WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("""
        SELECT * FROM library_items
        WHERE libraryId = :libraryId
          AND readingProgress > 0.0
          AND readingProgress < 1.0
        ORDER BY lastOpenedAt DESC NULLS LAST
    """)
    fun observeInProgress(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId AND readingProgress = 1.0 ORDER BY title ASC")
    fun observeFinished(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId ORDER BY title ASC")
    fun observeAllBooks(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("UPDATE library_items SET lastOpenedAt = :timestamp WHERE id = :itemId")
    suspend fun updateLastOpenedAt(itemId: String, timestamp: Long)

    @Query("SELECT id, lastOpenedAt FROM library_items WHERE libraryId = :libraryId AND lastOpenedAt IS NOT NULL")
    suspend fun getLastOpenedAtMap(libraryId: String): List<LastOpenedAtRow>
}

data class LastOpenedAtRow(val id: String, val lastOpenedAt: Long)
