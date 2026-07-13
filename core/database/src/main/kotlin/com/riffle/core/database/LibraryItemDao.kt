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

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId ORDER BY title ASC")
    fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId")
    suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity>

    /** All library items owned by a Source, across every library. Used by the Annotations View
     *  library list, which joins against highlight summaries that aren't library-scoped. */
    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId")
    fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>>

    @Query("""
        SELECT * FROM library_items
        WHERE sourceId = :sourceId AND libraryId = :libraryId
        AND id NOT IN (
            SELECT itemId FROM series_items
            WHERE sourceId = :sourceId
              AND seriesId IN (SELECT id FROM series WHERE libraryId = :libraryId)
        )
        AND id NOT IN (
            SELECT itemId FROM collection_items
            WHERE sourceId = :sourceId
              AND collectionId IN (SELECT id FROM collections WHERE libraryId = :libraryId)
        )
        ORDER BY title ASC
    """)
    fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

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

    /** Removes library items whose id is no longer present on the server. Bounded ID list; see
     *  [replaceAllForLibrary] for how the caller chunks the input. */
    @Query("DELETE FROM library_items WHERE sourceId = :sourceId AND id IN (:itemIds)")
    suspend fun deleteByIds(sourceId: String, itemIds: List<String>)

    /** All ids currently persisted for `(sourceId, libraryId)`. Used by [replaceAllForLibrary] to
     *  compute the delete-set client-side, avoiding a `NOT IN (?, ?, …)` bind list that would
     *  blow past SQLite's 999-variable ceiling on API 25 for large Komga libraries (#528). */
    @Query("SELECT id FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId")
    suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String>

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND id = :itemId LIMIT 1")
    suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND id IN (:itemIds)")
    suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity>

    /** Reactive single-item read — re-emits when the row changes (e.g. readingProgress on reader close). */
    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND id = :itemId LIMIT 1")
    fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?>

    /**
     * The Source that owns an item id. Used by the one-time on-disk file migration (ADR 0025) to
     * relocate legacy flat `<itemId>` files under their owning Source. Pre-migration item ids were
     * globally unique (DB PK was itemId), so at most one row matches.
     */
    @Query("SELECT sourceId FROM library_items WHERE id = :itemId LIMIT 1")
    suspend fun findSourceIdForItem(itemId: String): String?

    @Query("DELETE FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId")
    suspend fun deleteByLibraryId(sourceId: String, libraryId: String)

    @Query("DELETE FROM library_items WHERE sourceId = :sourceId AND id = :itemId")
    suspend fun deleteById(sourceId: String, itemId: String)

    @Transaction
    suspend fun replaceAllForLibrary(sourceId: String, libraryId: String, items: List<LibraryItemEntity>) {
        if (items.isEmpty()) {
            deleteByLibraryId(sourceId, libraryId)
            return
        }
        // Compute the delete-set client-side rather than passing every server id into a
        // `NOT IN (?, ?, …)` bind list — SQLite on API 25 caps bound variables at 999, and Komga's
        // Comics library has 2000+ books (#528). Chunk the resulting DELETE too, in case a large
        // library has thousands of stale rows to prune in one refresh.
        val serverIds = items.mapTo(HashSet(items.size)) { it.id }
        val toDelete = idsForLibrary(sourceId, libraryId).filterNot { it in serverIds }
        toDelete.chunked(SQLITE_MAX_BIND_ARGS).forEach { chunk -> deleteByIds(sourceId, chunk) }
        // Insert truly new items — they get the server's readingProgress as the initial seed.
        insertOrIgnore(items)
        // Update metadata for all items, preserving each row's local readingProgress.
        items.forEach { updateMetadata(LibraryItemMetadata.from(it)) }
    }

    companion object {
        // SQLite's SQLITE_MAX_VARIABLE_NUMBER is 999 on API 25 and 32766 on newer builds. Room's
        // `id IN (:list)` expansion binds one `?` per element plus 1 for `sourceId`, so keep the
        // chunk well below the floor.
        private const val SQLITE_MAX_BIND_ARGS = 900
    }

    @Query("""
        SELECT * FROM library_items
        WHERE sourceId = :sourceId AND libraryId = :libraryId
          AND readingProgress > 0.0
          AND readingProgress < 0.99
        ORDER BY lastOpenedAt IS NULL ASC, lastOpenedAt DESC
    """)
    fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId AND readingProgress >= 0.99 ORDER BY COALESCE(finishedAt, lastOpenedAt) IS NULL ASC, COALESCE(finishedAt, lastOpenedAt) DESC")
    fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    // `addedAt > 0` filters out sentinel rows written by on-demand browse upserters
    // (e.g. WebSourceLibraryItemUpserter). A tap-through-the-browser is not intent to add the book
    // to the library — the row exists only so the reader can resolve the item. updateLastOpenedAt
    // promotes the sentinel to a real timestamp on the first reader open.
    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId AND addedAt > 0 ORDER BY addedAt DESC")
    fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId ORDER BY title ASC")
    fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>>

    // Opening the reader is the strong-intent signal that promotes a browse-cached row (addedAt = 0
    // sentinel, written by on-demand upserters like WebSourceLibraryItemUpserter) into "genuinely
    // added" so it surfaces in Recently Added. Rows with a real addedAt keep their original stamp.
    @Query(
        "UPDATE library_items SET lastOpenedAt = :timestamp, " +
            "addedAt = CASE WHEN addedAt = 0 THEN :timestamp ELSE addedAt END " +
            "WHERE sourceId = :sourceId AND id = :itemId"
    )
    suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long)

    @Query("UPDATE library_items SET readingProgress = :progress WHERE sourceId = :sourceId AND id = :itemId")
    suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float)

    /**
     * Conditional variant used by [LibraryRepositoryImpl.refreshLibraryItems] to adopt the
     * server's `readingProgress` on a later refresh when the row was initially inserted at 0
     * (e.g. the first pullAllProgress missed it). The Kotlin-side caller gates on the pre-refresh
     * `lastOpenedAt` snapshot; this SQL clause only guards against a concurrent reader-close race
     * — a fresh non-zero local progress must not be clobbered even if it arrives between the
     * pre-refresh map fetch and this UPDATE (#528).
     */
    @Query("UPDATE library_items SET readingProgress = :progress WHERE sourceId = :sourceId AND id = :itemId AND readingProgress = 0")
    suspend fun updateInitialReadingProgress(sourceId: String, itemId: String, progress: Float)

    /**
     * Retag a library item's [libraryId]. Used by the LocalFiles scanner so a book's compatibility
     * hint stays pointed at some *currently-configured* folder library — otherwise removing that
     * folder leaves the row naming a deleted [LibraryEntity]. Catalog queries for LocalFiles go
     * through `local_files_file_folders`, so this column is authoritative only outside LocalFiles.
     */
    @Query("UPDATE library_items SET libraryId = :libraryId WHERE sourceId = :sourceId AND id = :itemId")
    suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String)

    @Query("UPDATE library_items SET finishedAt = :finishedAt WHERE sourceId = :sourceId AND id = :itemId")
    suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?)

    @Query("SELECT id, lastOpenedAt FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId AND lastOpenedAt IS NOT NULL")
    suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow>

    @Query("SELECT id, readingProgress FROM library_items WHERE sourceId = :sourceId AND libraryId = :libraryId AND readingProgress > 0.0")
    suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow>

    /**
     * All library items whose owning Source is of the given [serverType]. Used by the
     * Storyteller↔ABS matcher to enumerate candidates across every configured Source of a
     * side. No format filter — `readaloud_links` is now keyed by ABS item, so an audiobook-
     * stub entry coexisting with an ebook entry produces two link rows rather than a Tier 2
     * collision.
     *
     * Joined against [sources] via `library_items.sourceId`, NOT through `libraries.id`:
     * library ids are only unique within a Source (issue #113), so joining by library id
     * multiplies each item by every Source whose library happens to share that id, which
     * surfaces as a duplicate-key crash in the Readaloud picker's LazyColumn.
     */
    @Query(
        "SELECT li.id AS itemId, li.sourceId AS sourceId, li.title, li.author, li.isbn, li.asin " +
            "FROM library_items li " +
            "JOIN sources s ON li.sourceId = s.id " +
            "WHERE s.serverType = :serverType"
    )
    suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow>
}

/**
 * Partial-update POJO for [LibraryItemEntity] that excludes [LibraryItemEntity.readingProgress].
 * Used by [LibraryItemDao.updateMetadata] so that library refreshes never overwrite locally-tracked
 * reading progress with a stale server value.
 */
data class LibraryItemMetadata(
    val sourceId: String,
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
    val addedAt: Long,
    val isbn: String?,
    val asin: String?,
    val finishedAt: Long?,
) {
    companion object {
        fun from(entity: LibraryItemEntity) = LibraryItemMetadata(
            sourceId = entity.sourceId,
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
    val sourceId: String,
    val title: String,
    val author: String,
    val isbn: String?,
    val asin: String?,
)
