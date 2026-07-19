package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    /** Live, non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Live, non-deleted annotations across every item for a source, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForSource(sourceId: String): Flow<List<AnnotationEntity>>

    /** One-shot read of non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity>

    /** One-shot read of every row for an item, **including tombstones** (deleted = 1). Used by
     *  AnnotationSyncController.pushPending so tombstones can propagate to other devices. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnotationEntity?

    /** One-shot lookup of the live (non-deleted) annotation matching this exact CFI in this item.
     *  Used at open-from-library to bind openAtCfi back to its source annotation so continuous mode
     *  can scroll to the `<mark data-riffle-ann="<id>">` decoration once it's been applied. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId AND cfi = :cfi " +
            "AND deleted = 0 LIMIT 1"
    )
    suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity?

    /** One-shot lookup of the live (non-deleted) `TYPE_IMAGE` annotation already anchored to this
     *  figure in this chapter, or null if the figure hasn't been annotated yet. The caller passes
     *  exactly one of [imageHref] / [imageSvg] (mirroring [AnnotationDao.upsert]'s figure split) —
     *  the other stays null, and `col = :param` in SQLite never matches a null column-to-param pair,
     *  so both sides use `(:param IS NULL OR col = :param)` to make the unset side a no-op filter
     *  instead of excluding every row. Used by `EpubReaderViewModel.onFigureLongPress` (Task 11) to
     *  dispatch edit-vs-create instead of stacking a duplicate `TYPE_IMAGE` row per long-press. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId " +
            "AND chapterHref = :chapterHref AND type = 'IMAGE' AND deleted = 0 " +
            "AND (:imageHref IS NULL OR imageHref = :imageHref) " +
            "AND (:imageSvg IS NULL OR imageSvg = :imageSvg) " +
            "LIMIT 1"
    )
    suspend fun findImageForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ): AnnotationEntity?

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

    /** Replace the styles set on an emphasis row in place, bumping updatedAt + provenance (ADR 0046).
     *  The type = 'EMPHASIS' predicate keeps a caller error from accidentally mutating a highlight.
     *  Returns the row count updated so the store can distinguish a stale-id miss from a real edit
     *  (see code-review F7); zero means the update landed on nothing and no sync push is needed. */
    @Query(
        "UPDATE annotations SET emphasisStyles = :emphasisStyles, updatedAt = :updatedAt, " +
            "lastModifiedByDeviceId = :deviceId WHERE id = :id AND type = 'EMPHASIS' AND deleted = 0"
    )
    suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int

    /** Live, non-deleted annotations for an item sorted by reading position (spine order, then
     *  within-chapter). Ties on (spineIndex, progression) — common when a bookmark at chapter top
     *  and a highlight on the first word both resolve to progression=0.0 — fall back to createdAt
     *  and then id, so the order stays stable across sync-driven upserts. */
    @Query(
        "SELECT * FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY spineIndex ASC, progression ASC, createdAt ASC, id ASC"
    )
    fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** Update the user-editable title of a bookmark, bumping updatedAt + provenance. */
    @Query("UPDATE annotations SET bookmarkTitle = :title, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id AND type = 'BOOKMARK'")
    suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String)

    /**
     * Backfill `originFontFamily` for every legacy null-font row on this book (issue #484). Fires
     * when the reader opens the source book and the WebView reports its computed body font.
     * `deleted = 0` guards against pushing tombs back to peers on account of a font-only change,
     * and `updatedAt`/`lastModifiedByDeviceId` bumps ensure the change propagates through sync.
     * Only runs for rows where the column is still null: subsequent opens are cheap no-ops.
     */
    @Query(
        "UPDATE annotations SET originFontFamily = :fontFamily, updatedAt = :updatedAt, " +
            "lastModifiedByDeviceId = :deviceId " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "AND originFontFamily IS NULL"
    )
    suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int

    /**
     * Heal every row whose `originFontFamily` equals the [sentinel] value (`"serif"`, written by
     * legacy annotation-create paths when the live WebView probe returned nothing — see
     * `EpubReaderViewModel.FALLBACK_ORIGIN_FONT_FAMILY`) to the freshly reported publisher font
     * for this book. Guarded on `fontFamily != sentinel` at the call site — a book whose CSS
     * legitimately sets `body { font-family: serif }` reports back the sentinel value verbatim
     * and must not trigger an updatedAt bump on every open (would churn sync).
     *
     * Runs alongside [backfillNullOriginFontFamily] on the first non-blank body-font report per
     * book per session; both queries then no-op on subsequent chapter loads. Same provenance +
     * timestamp rules apply so peers see one consolidated update.
     */
    @Query(
        "UPDATE annotations SET originFontFamily = :fontFamily, updatedAt = :updatedAt, " +
            "lastModifiedByDeviceId = :deviceId " +
            "WHERE sourceId = :sourceId AND itemId = :itemId AND deleted = 0 " +
            "AND originFontFamily = :sentinel"
    )
    suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int

    /** Pending-row count for one book — live Flow for reader-chrome and per-book status. */
    @Query("SELECT COUNT(*) FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId AND updatedAt > lastSyncedAt")
    fun observePendingCountForBook(sourceId: String, itemId: String): Flow<Int>

    /** Count of distinct books with at least one dirty annotation — live Flow for the Settings
     *  list-row badge. Scoped by book, not by annotation row, so the "N book(s) pending" wording
     *  actually matches: five dirty highlights on one book still reads as "1 book pending". */
    @Query(
        "SELECT COUNT(*) FROM (SELECT DISTINCT sourceId, itemId FROM annotations " +
            "WHERE updatedAt > lastSyncedAt)"
    )
    fun observePendingBookCountAcrossAll(): Flow<Int>

    /** One row per `(sourceId, itemId)` with at least one dirty annotation. Used by AnnotationSweep. */
    @Query("SELECT DISTINCT sourceId, itemId FROM annotations WHERE updatedAt > lastSyncedAt")
    suspend fun dirtySourceItems(): List<DirtySourceItem>

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
        "DELETE FROM annotations WHERE sourceId = :sourceId AND itemId = :itemId " +
            "AND deleted = 1 AND updatedAt < :cutoff AND updatedAt <= lastSyncedAt"
    )
    suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int

    /** One row per book (ABS Library Item) with at least one live highlight or image annotation on
     *  this source, most recently updated first. Powers the Annotations View library list.
     *  Bookmarks are excluded — they have their own tab. Image annotations are first-class here
     *  since a user may annotate figures without ever making a text highlight. Format-only
     *  highlight anchors (color="" + emphasis) count too — they represent a real user-created
     *  annotation ("just bold this text"); rendering swaps the yellow dot for a neutral marker,
     *  handled by the UI layer (see [com.riffle.app.feature.reader.AnnotationsPanel]). */
    @Query(
        """
        SELECT itemId,
               COUNT(*) AS highlightCount,
               MAX(updatedAt) AS latestUpdatedAt
        FROM annotations
        WHERE sourceId = :sourceId
          AND type IN ('HIGHLIGHT', 'IMAGE')
          AND deleted = 0
        GROUP BY itemId
        ORDER BY latestUpdatedAt DESC
    """
    )
    fun observeBooksWithHighlights(sourceId: String): Flow<List<BookHighlightSummary>>

    /** Result row for [dirtySourceItems]. */
    data class DirtySourceItem(val sourceId: String, val itemId: String)
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
