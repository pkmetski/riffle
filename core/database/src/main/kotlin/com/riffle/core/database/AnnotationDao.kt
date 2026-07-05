package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    /** Live, non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Live, non-deleted annotations across every item for a server, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForServer(serverId: String): Flow<List<AnnotationEntity>>

    /** One-shot read of non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity>

    /** One-shot read of every row for an item, **including tombstones** (deleted = 1). Used by
     *  AnnotationSyncController.pushPending so tombstones can propagate to other devices. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getAllForItemIncludingDeleted(serverId: String, itemId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnotationEntity?

    /** One-shot lookup of the live (non-deleted) annotation matching this exact CFI in this item.
     *  Used at open-from-library to bind openAtCfi back to its source annotation so continuous mode
     *  can scroll to the `<mark data-riffle-ann="<id>">` decoration once it's been applied. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND cfi = :cfi " +
            "AND deleted = 0 LIMIT 1"
    )
    suspend fun getByItemAndCfi(serverId: String, itemId: String, cfi: String): AnnotationEntity?

    // Real UPSERT (not INSERT-OR-REPLACE): REPLACE reallocates the rowid on every update, which
    // shuffles the SQLite fallback order for rows that tie on the sort key (e.g. a bookmark and a
    // highlight both at spineIndex=N, progression=0.0 at the top of a chapter). Every sync
    // round-trip re-upserts and swaps their order in observeAnnotationsByPosition.
    @Upsert
    suspend fun upsert(entity: AnnotationEntity)

    @Upsert
    suspend fun upsertAll(annotations: List<AnnotationEntity>)

    /** Tombstone an annotation so the delete can later propagate to other devices. */
    @Query("UPDATE annotations SET deleted = 1, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun tombstone(id: String, updatedAt: Long, deviceId: String)

    /** Recolour an annotation in place, bumping updatedAt + provenance so the change can propagate. */
    @Query("UPDATE annotations SET color = :color, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String)

    /** Set (or clear) the note on a highlight in place, bumping updatedAt + provenance. */
    @Query("UPDATE annotations SET note = :note, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String)

    /** Live, non-deleted annotations for an item sorted by reading position (spine order, then
     *  within-chapter). Ties on (spineIndex, progression) — common when a bookmark at chapter top
     *  and a highlight on the first word both resolve to progression=0.0 — fall back to createdAt
     *  and then id, so the order stays stable across sync-driven upserts. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY spineIndex ASC, progression ASC, createdAt ASC, id ASC"
    )
    fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Update the user-editable title of a bookmark, bumping updatedAt + provenance. */
    @Query("UPDATE annotations SET bookmarkTitle = :title, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id AND type = 'BOOKMARK'")
    suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String)

    /** Pending-row count for one book — live Flow for reader-chrome and per-book status. */
    @Query("SELECT COUNT(*) FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND updatedAt > lastSyncedAt")
    fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int>

    /** Count of distinct books with at least one dirty annotation — live Flow for the Settings
     *  list-row badge. Scoped by book, not by annotation row, so the "N book(s) pending" wording
     *  actually matches: five dirty highlights on one book still reads as "1 book pending". */
    @Query(
        "SELECT COUNT(*) FROM (SELECT DISTINCT serverId, itemId FROM annotations " +
            "WHERE updatedAt > lastSyncedAt)"
    )
    fun observePendingBookCountAcrossAll(): Flow<Int>

    /** One row per `(serverId, itemId)` with at least one dirty annotation. Used by AnnotationSweep. */
    @Query("SELECT DISTINCT serverId, itemId FROM annotations WHERE updatedAt > lastSyncedAt")
    suspend fun dirtyServerItems(): List<DirtyServerItem>

    /** Stamp the given row ids as synced at the given wall-clock timestamp. */
    @Query("UPDATE annotations SET lastSyncedAt = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    /**
     * ADR 0038 — hard-delete tombstones whose `updatedAt` is older than [cutoff]. The
     * `updatedAt <= lastSyncedAt` guard restricts purge to tombs whose current state has been
     * pushed successfully from this device at least once; a tomb that never made it to WebDAV
     * (offline at delete time) is preserved until it does, so peers still receive the delete.
     * Returns the row count purged.
     */
    @Query(
        "DELETE FROM annotations WHERE serverId = :serverId AND itemId = :itemId " +
            "AND deleted = 1 AND updatedAt < :cutoff AND updatedAt <= lastSyncedAt"
    )
    suspend fun purgeAgedTombstones(serverId: String, itemId: String, cutoff: Long): Int

    /** One row per book (ABS Library Item) with at least one live highlight on this server, most
     *  recently updated first. Powers the Annotations View library list. */
    @Query(
        """
        SELECT itemId,
               COUNT(*) AS highlightCount,
               MAX(updatedAt) AS latestUpdatedAt
        FROM annotations
        WHERE serverId = :serverId
          AND type = 'HIGHLIGHT'
          AND deleted = 0
        GROUP BY itemId
        ORDER BY latestUpdatedAt DESC
    """
    )
    fun observeBooksWithHighlights(serverId: String): Flow<List<BookHighlightSummary>>

    /** Result row for [dirtyServerItems]. */
    data class DirtyServerItem(val serverId: String, val itemId: String)
}

/**
 * One row per book with at least one live highlight — powers the Annotations View library list
 * (see [AnnotationDao.observeBooksWithHighlights]).
 */
data class BookHighlightSummary(
    val itemId: String,
    val highlightCount: Int,
    val latestUpdatedAt: Long,
)
