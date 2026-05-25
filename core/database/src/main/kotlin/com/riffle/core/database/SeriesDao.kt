package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {

    @Query("SELECT * FROM series WHERE libraryId = :libraryId ORDER BY name ASC")
    fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>>

    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN series_items si ON li.id = si.itemId
        WHERE si.seriesId = :seriesId
        ORDER BY si.sequenceOrder ASC
    """)
    fun observeItemsBySeriesId(seriesId: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllItems(items: List<SeriesItemEntity>)

    @Query("DELETE FROM series WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("DELETE FROM series_items WHERE seriesId IN (SELECT id FROM series WHERE libraryId = :libraryId)")
    suspend fun deleteItemsByLibraryId(libraryId: String)

    @Transaction
    suspend fun replaceAllForLibrary(
        libraryId: String,
        series: List<SeriesEntity>,
        seriesItems: List<SeriesItemEntity>,
    ) {
        deleteItemsByLibraryId(libraryId)
        deleteByLibraryId(libraryId)
        upsertAll(series)
        upsertAllItems(seriesItems)
    }
}
