package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryItemDao {

    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId ORDER BY title ASC")
    fun observeByLibraryId(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    /** All library items owned by a Server, across every library. Used by the Annotations View
     *  library list, which joins against highlight summaries that aren't library-scoped. */
    @Query("SELECT * FROM library_items WHERE serverId = :serverId")
    fun observeByServer(serverId: String): Flow<List<LibraryItemEntity>>

    @Query("""
        SELECT * FROM library_items
        WHERE serverId = :serverId AND libraryId = :libraryId
        AND id NOT IN (
            SELECT itemId FROM series_items
            WHERE serverId = :serverId
              AND seriesId IN (SELECT id FROM series WHERE libraryId = :libraryId)
        )
        AND id NOT IN (
            SELECT itemId FROM collection_items
            WHERE serverId = :serverId
              AND collectionId IN (SELECT id FROM collections WHERE libraryId = :libraryId)
        )
        ORDER BY title ASC
    """)
    fun observeUngroupedByLibraryId(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    /** Inserts items that do not yet exist; skips rows that already have a matching primary key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(items: List<LibraryItemEntity>)

    /**
     * Updates all metadata columns for an existing row, intentionally excluding [readingProgress].
     * Progress is owned by the reader-close path ([updateReadingProgress]) and must not be
     * overwritten by a library refresh carrying a stale server value.
     */
    @Update(entity = LibraryItemEntity::class)
    suspend fun updateMetadata(metadata: LibraryItemMetadata)

    /** Removes library items whose id is no longer present on the server. */
    @Query("DELETE FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId AND id NOT IN (:serverItemIds)")
    suspend fun deleteRemovedFromLibrary(serverId: String, libraryId: String, serverItemIds: List<String>)

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

    @Query("DELETE FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId")
    suspend fun deleteByLibraryId(serverId: String, libraryId: String)

    @Transaction
    suspend fun replaceAllForLibrary(serverId: String, libraryId: String, items: List<LibraryItemEntity>) {
        if (items.isEmpty()) {
            deleteByLibraryId(serverId, libraryId)
            return
        }
        // Remove items no longer present on the server.
        deleteRemovedFromLibrary(serverId, libraryId, items.map { it.id })
        // Insert truly new items — they get the server's readingProgress as the initial seed.
        insertOrIgnore(items)
        // Update metadata for all items, preserving each row's local readingProgress.
        items.forEach { updateMetadata(LibraryItemMetadata.from(it)) }
    }

    @Query("""
        SELECT * FROM library_items
        WHERE serverId = :serverId AND libraryId = :libraryId
          AND readingProgress > 0.0
          AND readingProgress < 0.99
        ORDER BY lastOpenedAt IS NULL ASC, lastOpenedAt DESC
    """)
    fun observeInProgress(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId AND readingProgress >= 0.99 ORDER BY COALESCE(finishedAt, lastOpenedAt) IS NULL ASC, COALESCE(finishedAt, lastOpenedAt) DESC")
    fun observeFinished(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId ORDER BY addedAt IS NULL ASC, addedAt DESC")
    fun observeRecentlyAdded(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId ORDER BY title ASC")
    fun observeAllBooks(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("UPDATE library_items SET lastOpenedAt = :timestamp WHERE serverId = :serverId AND id = :itemId")
    suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long)

    @Query("UPDATE library_items SET readingProgress = :progress WHERE serverId = :serverId AND id = :itemId")
    suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float)

    @Query("UPDATE library_items SET finishedAt = :finishedAt WHERE serverId = :serverId AND id = :itemId")
    suspend fun updateFinishedAt(serverId: String, itemId: String, finishedAt: Long?)

    @Query("SELECT id, lastOpenedAt FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId AND lastOpenedAt IS NOT NULL")
    suspend fun getLastOpenedAtMap(serverId: String, libraryId: String): List<LastOpenedAtRow>

    @Query("SELECT id, readingProgress FROM library_items WHERE serverId = :serverId AND libraryId = :libraryId AND readingProgress > 0.0")
    suspend fun getReadingProgressMap(serverId: String, libraryId: String): List<ReadingProgressRow>

    /**
     * All library items whose owning Server is of the given [serverType]. Used by the
     * Storyteller↔ABS matcher to enumerate candidates across every configured Server of a
     * side. No format filter — `readaloud_links` is now keyed by ABS item, so an audiobook-
     * stub entry coexisting with an ebook entry produces two link rows rather than a Tier 2
     * collision.
     *
     * Joined against [servers] via `library_items.serverId`, NOT through `libraries.id`:
     * library ids are only unique within a Server (issue #113), so joining by library id
     * multiplies each item by every Server whose library happens to share that id, which
     * surfaces as a duplicate-key crash in the Readaloud picker's LazyColumn.
     */
    @Query(
        "SELECT li.id AS itemId, li.serverId AS serverId, li.title, li.author, li.isbn, li.asin " +
            "FROM library_items li " +
            "JOIN servers s ON li.serverId = s.id " +
            "WHERE s.serverType = :serverType"
    )
    suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow>
}

/**
 * Partial-update POJO for [LibraryItemEntity] that excludes [LibraryItemEntity.readingProgress].
 * Used by [LibraryItemDao.updateMetadata] so that library refreshes never overwrite locally-tracked
 * reading progress with a stale server value.
 */
data class LibraryItemMetadata(
    val serverId: String,
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val ebookFileIno: String?,
    val ebookFormat: String,
    val hasAudio: Boolean,
    val audioDurationSec: Double,
    val description: String?,
    val seriesName: String?,
    val publishedYear: String?,
    val genres: String,
    val publisher: String?,
    val language: String?,
    val lastOpenedAt: Long?,
    val addedAt: Long?,
    val isbn: String?,
    val asin: String?,
    val finishedAt: Long?,
) {
    companion object {
        fun from(entity: LibraryItemEntity) = LibraryItemMetadata(
            serverId = entity.serverId,
            id = entity.id,
            libraryId = entity.libraryId,
            title = entity.title,
            author = entity.author,
            coverUrl = entity.coverUrl,
            ebookFileIno = entity.ebookFileIno,
            ebookFormat = entity.ebookFormat,
            hasAudio = entity.hasAudio,
            audioDurationSec = entity.audioDurationSec,
            description = entity.description,
            seriesName = entity.seriesName,
            publishedYear = entity.publishedYear,
            genres = entity.genres,
            publisher = entity.publisher,
            language = entity.language,
            lastOpenedAt = entity.lastOpenedAt,
            addedAt = entity.addedAt,
            isbn = entity.isbn,
            asin = entity.asin,
            finishedAt = entity.finishedAt,
        )
    }
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
