package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND id = :itemId LIMIT 1")
    suspend fun getById(serverId: String, itemId: String): LibraryItemEntity?

    /** Reactive single-item read — re-emits when the row changes (e.g. readingProgress on reader close). */
    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND id = :itemId LIMIT 1")
    fun observeById(serverId: String, itemId: String): Flow<LibraryItemEntity?>

    /**
     * The Server that owns an item id. Used by the one-time on-disk file migration (ADR 0025) to
     * relocate legacy flat `<itemId>` files under their owning Server. Pre-migration item ids were
     * globally unique (DB PK was itemId), so at most one row matches.
     */
    @Query("SELECT serverId FROM library_items WHERE id = :itemId LIMIT 1")
    suspend fun findServerIdForItem(itemId: String): String?

    @Query("DELETE FROM library_items WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Transaction
    suspend fun replaceAllForLibrary(libraryId: String, items: List<LibraryItemEntity>) {
        deleteByLibraryId(libraryId)
        upsertAll(items)
    }

    @Query("""
        SELECT * FROM library_items
        WHERE libraryId = :libraryId
          AND readingProgress > 0.0
          AND readingProgress < 0.99
        ORDER BY lastOpenedAt IS NULL ASC, lastOpenedAt DESC
    """)
    fun observeInProgress(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId AND readingProgress >= 0.99 ORDER BY title ASC")
    fun observeFinished(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId ORDER BY addedAt IS NULL ASC, addedAt DESC")
    fun observeRecentlyAdded(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE libraryId = :libraryId ORDER BY title ASC")
    fun observeAllBooks(libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("UPDATE library_items SET lastOpenedAt = :timestamp WHERE serverId = :serverId AND id = :itemId")
    suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long)

    @Query("UPDATE library_items SET readingProgress = :progress WHERE serverId = :serverId AND id = :itemId")
    suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float)

    @Query("SELECT id, lastOpenedAt FROM library_items WHERE libraryId = :libraryId AND lastOpenedAt IS NOT NULL")
    suspend fun getLastOpenedAtMap(libraryId: String): List<LastOpenedAtRow>

    @Query("SELECT id, readingProgress FROM library_items WHERE libraryId = :libraryId AND readingProgress > 0.0")
    suspend fun getReadingProgressMap(libraryId: String): List<ReadingProgressRow>

    /**
     * All library items whose owning Library lives on a Server of the given [serverType].
     * Used by the Storyteller↔ABS matcher to enumerate candidates across every configured
     * Server of a side. No format filter — `readaloud_links` is now keyed by ABS item, so
     * an audiobook-stub entry coexisting with an ebook entry produces two link rows
     * rather than a Tier 2 collision.
     */
    @Query(
        "SELECT li.id AS itemId, lib.serverId AS serverId, li.title, li.author, li.isbn, li.asin " +
            "FROM library_items li " +
            "JOIN libraries lib ON li.libraryId = lib.id " +
            "JOIN servers s ON lib.serverId = s.id " +
            "WHERE s.serverType = :serverType"
    )
    suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow>
}

data class LastOpenedAtRow(val id: String, val lastOpenedAt: Long)
data class ReadingProgressRow(val id: String, val readingProgress: Float)
data class MatchableItemRow(
    val itemId: String,
    val serverId: String,
    val title: String,
    val author: String,
    val isbn: String?,
    val asin: String?,
)
