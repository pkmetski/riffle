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
        INNER JOIN series_items si ON li.serverId = si.serverId AND li.id = si.itemId
        WHERE si.seriesId = :seriesId
        ORDER BY si.sequenceOrder ASC
    """)
    fun observeItemsBySeriesId(seriesId: String): Flow<List<LibraryItemEntity>>

    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN series_items si ON li.serverId = si.serverId AND li.id = si.itemId
        WHERE li.libraryId = :libraryId
          AND li.readingProgress < 1.0
          AND si.seriesId IN (
              SELECT DISTINCT si2.seriesId
              FROM series_items si2
              INNER JOIN library_items li2 ON li2.serverId = si2.serverId AND li2.id = si2.itemId
              WHERE li2.libraryId = :libraryId AND li2.readingProgress >= 0.99
          )
          AND si.seriesId NOT IN (
              SELECT DISTINCT si5.seriesId
              FROM series_items si5
              INNER JOIN library_items li5 ON li5.serverId = si5.serverId AND li5.id = si5.itemId
              WHERE li5.libraryId = :libraryId
                AND li5.readingProgress >= 0.05
                AND li5.readingProgress < 0.99
          )
          AND si.sequenceOrder = (
              SELECT MIN(si3.sequenceOrder)
              FROM series_items si3
              INNER JOIN library_items li3 ON li3.serverId = si3.serverId AND li3.id = si3.itemId
              WHERE si3.seriesId = si.seriesId
                AND li3.libraryId = :libraryId
                AND li3.readingProgress < 0.99
          )
        ORDER BY COALESCE(
            (
                SELECT MAX(li4.lastOpenedAt)
                FROM series_items si4
                INNER JOIN library_items li4 ON li4.serverId = si4.serverId AND li4.id = si4.itemId
                WHERE si4.seriesId = si.seriesId
                  AND li4.libraryId = :libraryId
                  AND li4.readingProgress >= 0.99
            ), 0
        ) DESC
    """)
    fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItemEntity>>

    /**
     * The id of the series an item belongs to, if any. Used by the Library Item Detail Screen to
     * make the series line tap through to the existing Series detail (an item carries only its
     * `seriesName` string, not the series id).
     */
    @Query("SELECT seriesId FROM series_items WHERE serverId = :serverId AND itemId = :itemId LIMIT 1")
    suspend fun findSeriesIdForItem(serverId: String, itemId: String): String?

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
