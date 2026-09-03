package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.BookHighlightSummary
import com.riffle.core.database.DirtySourceItem
import com.riffle.core.database.IosInvalidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

@Suppress("TooManyFunctions")
internal class IosAnnotationDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : AnnotationDao {
    override fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? AND deleted = 0 ORDER BY createdAt ASC",
                    ::mapRows, 2) { bindString(0, sourceId); bindString(1, itemId) }.value)
            }
        }

    override fun observeForSource(sourceId: String): Flow<List<AnnotationEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND deleted = 0 ORDER BY createdAt ASC",
                    ::mapRows, 1) { bindString(0, sourceId) }.value)
            }
        }

    override suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity> =
        driver.executeQuery(null,
            "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? AND deleted = 0 ORDER BY createdAt ASC",
            ::mapRows, 2) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity> =
        driver.executeQuery(null,
            "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? ORDER BY createdAt ASC",
            ::mapRows, 2) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun getById(id: String): AnnotationEntity? =
        driver.executeQuery(null,
            "SELECT $ALL_COLS FROM annotations WHERE id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toAnnotationEntity() else null) },
            1) { bindString(0, id) }.value

    override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? =
        driver.executeQuery(null,
            "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? AND cfi = ? AND deleted = 0 LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toAnnotationEntity() else null) },
            3) { bindString(0, sourceId); bindString(1, itemId); bindString(2, cfi) }.value

    override suspend fun findImageForFigure(
        sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
    ): AnnotationEntity? =
        driver.executeQuery(null,
            "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? AND chapterHref = ? " +
                "AND type = 'IMAGE' AND deleted = 0 AND (? IS NULL OR imageHref = ?) AND (? IS NULL OR imageSvg = ?) LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toAnnotationEntity() else null) },
            7) {
            bindString(0, sourceId); bindString(1, itemId); bindString(2, chapterHref)
            bindString(3, imageHref); bindString(4, imageHref)
            bindString(5, imageSvg); bindString(6, imageSvg)
        }.value

    override suspend fun upsert(entity: AnnotationEntity) {
        executeUpsert(entity)
        invalidator.invalidate()
    }

    override suspend fun upsertAll(annotations: List<AnnotationEntity>) {
        annotations.forEach { executeUpsert(it) }
        if (annotations.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {
        driver.execute(null,
            "UPDATE annotations SET deleted = 1, updatedAt = MAX(updatedAt + 1, ?), lastModifiedByDeviceId = ? WHERE id = ?",
            3) { bindLong(0, updatedAt); bindString(1, deviceId); bindString(2, id) }
        invalidator.invalidate()
    }

    override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {
        driver.execute(null,
            "UPDATE annotations SET color = ?, updatedAt = MAX(updatedAt + 1, ?), lastModifiedByDeviceId = ? WHERE id = ?",
            4) { bindString(0, color); bindLong(1, updatedAt); bindString(2, deviceId); bindString(3, id) }
        invalidator.invalidate()
    }

    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {
        driver.execute(null,
            "UPDATE annotations SET note = ?, updatedAt = MAX(updatedAt + 1, ?), lastModifiedByDeviceId = ? WHERE id = ?",
            4) { bindString(0, note); bindLong(1, updatedAt); bindString(2, deviceId); bindString(3, id) }
        invalidator.invalidate()
    }

    override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int {
        driver.execute(null,
            "UPDATE annotations SET emphasisStyles = ?, updatedAt = MAX(updatedAt + 1, ?), lastModifiedByDeviceId = ? " +
                "WHERE id = ? AND type = 'EMPHASIS' AND deleted = 0",
            4) { bindString(0, emphasisStyles); bindLong(1, updatedAt); bindString(2, deviceId); bindString(3, id) }
        val count = driver.executeQuery(null, "SELECT changes()", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) }, 0).value
        if (count > 0) invalidator.invalidate()
        return count
    }

    override fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    "SELECT $ALL_COLS FROM annotations WHERE sourceId = ? AND itemId = ? AND deleted = 0 " +
                        "ORDER BY spineIndex ASC, progression ASC, createdAt ASC, id ASC",
                    ::mapRows, 2) { bindString(0, sourceId); bindString(1, itemId) }.value)
            }
        }

    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
        driver.execute(null,
            "UPDATE annotations SET bookmarkTitle = ?, updatedAt = MAX(updatedAt + 1, ?), lastModifiedByDeviceId = ? WHERE id = ? AND type = 'BOOKMARK'",
            4) { bindString(0, title); bindLong(1, updatedAt); bindString(2, deviceId); bindString(3, id) }
        invalidator.invalidate()
    }

    override suspend fun backfillNullOriginFontFamily(
        sourceId: String, itemId: String, fontFamily: String, updatedAt: Long, deviceId: String,
    ): Int {
        driver.execute(null,
            "UPDATE annotations SET originFontFamily = ?, updatedAt = ?, lastModifiedByDeviceId = ? " +
                "WHERE sourceId = ? AND itemId = ? AND deleted = 0 AND originFontFamily IS NULL",
            5) {
            bindString(0, fontFamily); bindLong(1, updatedAt); bindString(2, deviceId)
            bindString(3, sourceId); bindString(4, itemId)
        }
        val count = driver.executeQuery(null, "SELECT changes()", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) }, 0).value
        if (count > 0) invalidator.invalidate()
        return count
    }

    override suspend fun healSentinelOriginFontFamily(
        sourceId: String, itemId: String, sentinel: String, fontFamily: String, updatedAt: Long, deviceId: String,
    ): Int {
        driver.execute(null,
            "UPDATE annotations SET originFontFamily = ?, updatedAt = ?, lastModifiedByDeviceId = ? " +
                "WHERE sourceId = ? AND itemId = ? AND deleted = 0 AND originFontFamily = ?",
            6) {
            bindString(0, fontFamily); bindLong(1, updatedAt); bindString(2, deviceId)
            bindString(3, sourceId); bindString(4, itemId); bindString(5, sentinel)
        }
        val count = driver.executeQuery(null, "SELECT changes()", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) }, 0).value
        if (count > 0) invalidator.invalidate()
        return count
    }

    override fun observePendingCountForBook(sourceId: String, itemId: String): Flow<Int> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    "SELECT COUNT(*) FROM annotations WHERE sourceId = ? AND itemId = ? AND updatedAt > lastSyncedAt",
                    { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
                    2) { bindString(0, sourceId); bindString(1, itemId) }.value)
            }
        }

    override fun observePendingBookCountAcrossAll(): Flow<Int> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    "SELECT COUNT(*) FROM (SELECT DISTINCT sourceId, itemId FROM annotations WHERE updatedAt > lastSyncedAt)",
                    { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
                    0).value)
            }
        }

    override suspend fun dirtySourceItems(): List<DirtySourceItem> =
        driver.executeQuery(null,
            "SELECT DISTINCT sourceId, itemId FROM annotations WHERE updatedAt > lastSyncedAt",
            { cursor ->
                val rows = mutableListOf<DirtySourceItem>()
                while (cursor.next().value) rows.add(DirtySourceItem(cursor.getString(0)!!, cursor.getString(1)!!))
                QueryResult.Value(rows)
            }, 0).value

    override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        driver.execute(null, "UPDATE annotations SET lastSyncedAt = ? WHERE id IN ($placeholders)",
            1 + ids.size) {
            bindLong(0, syncedAt)
            ids.forEachIndexed { i, id -> bindString(i + 1, id) }
        }
        invalidator.invalidate()
    }

    override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int {
        driver.execute(null,
            "DELETE FROM annotations WHERE sourceId = ? AND itemId = ? AND deleted = 1 AND updatedAt < ? AND updatedAt <= lastSyncedAt",
            3) { bindString(0, sourceId); bindString(1, itemId); bindLong(2, cutoff) }
        val count = driver.executeQuery(null, "SELECT changes()", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) }, 0).value
        if (count > 0) invalidator.invalidate()
        return count
    }

    override fun observeBooksWithHighlights(sourceId: String): Flow<List<BookHighlightSummary>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(null,
                    """SELECT itemId, COUNT(*) AS highlightCount, MAX(updatedAt) AS latestUpdatedAt
                       FROM annotations WHERE sourceId = ? AND type IN ('HIGHLIGHT', 'IMAGE') AND deleted = 0
                       GROUP BY itemId ORDER BY latestUpdatedAt DESC""",
                    { cursor ->
                        val rows = mutableListOf<BookHighlightSummary>()
                        while (cursor.next().value) rows.add(BookHighlightSummary(
                            itemId = cursor.getString(0)!!,
                            highlightCount = cursor.getLong(1)!!.toInt(),
                            latestUpdatedAt = cursor.getLong(2)!!,
                        ))
                        QueryResult.Value(rows)
                    }, 1) { bindString(0, sourceId) }.value)
            }
        }

    private fun executeUpsert(entity: AnnotationEntity) {
        driver.execute(null, """
            INSERT INTO annotations ($ALL_COLS) VALUES ($PLACEHOLDERS)
            ON CONFLICT(id) DO UPDATE SET
              sourceId = excluded.sourceId, itemId = excluded.itemId, type = excluded.type,
              cfi = excluded.cfi, color = excluded.color, note = excluded.note,
              textSnippet = excluded.textSnippet, textBefore = excluded.textBefore,
              textAfter = excluded.textAfter, chapterHref = excluded.chapterHref,
              spineIndex = excluded.spineIndex, progression = excluded.progression,
              bookmarkTitle = excluded.bookmarkTitle, createdAt = excluded.createdAt,
              updatedAt = excluded.updatedAt, originDeviceId = excluded.originDeviceId,
              lastModifiedByDeviceId = excluded.lastModifiedByDeviceId, deleted = excluded.deleted,
              lastSyncedAt = excluded.lastSyncedAt, embeddedFigures = excluded.embeddedFigures,
              imageHref = excluded.imageHref, imageSvg = excluded.imageSvg,
              imageBytes = excluded.imageBytes, originFontFamily = excluded.originFontFamily,
              emphasisStyles = excluded.emphasisStyles, textSnippetHtml = excluded.textSnippetHtml,
              fragmentAnchor = excluded.fragmentAnchor""", 28) {
            bindString(0, entity.id); bindString(1, entity.sourceId); bindString(2, entity.itemId)
            bindString(3, entity.type); bindString(4, entity.cfi); bindString(5, entity.color)
            bindString(6, entity.note); bindString(7, entity.textSnippet); bindString(8, entity.textBefore)
            bindString(9, entity.textAfter); bindString(10, entity.chapterHref)
            bindLong(11, entity.spineIndex.toLong()); bindDouble(12, entity.progression)
            bindString(13, entity.bookmarkTitle); bindLong(14, entity.createdAt)
            bindLong(15, entity.updatedAt); bindString(16, entity.originDeviceId)
            bindString(17, entity.lastModifiedByDeviceId)
            bindLong(18, if (entity.deleted) 1L else 0L); bindLong(19, entity.lastSyncedAt)
            bindString(20, entity.embeddedFigures); bindString(21, entity.imageHref)
            bindString(22, entity.imageSvg); bindString(23, entity.imageBytes)
            bindString(24, entity.originFontFamily); bindString(25, entity.emphasisStyles)
            bindString(26, entity.textSnippetHtml); bindString(27, entity.fragmentAnchor)
        }
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<AnnotationEntity>> {
        val result = mutableListOf<AnnotationEntity>()
        while (cursor.next().value) result.add(cursor.toAnnotationEntity())
        return QueryResult.Value(result)
    }

    private fun SqlCursor.toAnnotationEntity() = AnnotationEntity(
        id = getString(0)!!,
        sourceId = getString(1)!!,
        itemId = getString(2)!!,
        type = getString(3) ?: AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = getString(4)!!,
        color = getString(5) ?: AnnotationEntity.COLOR_YELLOW,
        note = getString(6),
        textSnippet = getString(7)!!,
        textBefore = getString(8) ?: "",
        textAfter = getString(9) ?: "",
        chapterHref = getString(10)!!,
        spineIndex = getLong(11)?.toInt() ?: 0,
        progression = getDouble(12) ?: 0.0,
        bookmarkTitle = getString(13) ?: "",
        createdAt = getLong(14)!!,
        updatedAt = getLong(15)!!,
        originDeviceId = getString(16)!!,
        lastModifiedByDeviceId = getString(17)!!,
        deleted = getLong(18)?.let { it != 0L } ?: false,
        lastSyncedAt = getLong(19) ?: 0L,
        embeddedFigures = getString(20),
        imageHref = getString(21),
        imageSvg = getString(22),
        imageBytes = getString(23),
        originFontFamily = getString(24),
        emphasisStyles = getString(25),
        textSnippetHtml = getString(26),
        fragmentAnchor = getString(27),
    )

    companion object {
        private const val ALL_COLS = "id, sourceId, itemId, type, cfi, color, note, textSnippet, textBefore, textAfter, " +
            "chapterHref, spineIndex, progression, bookmarkTitle, createdAt, updatedAt, " +
            "originDeviceId, lastModifiedByDeviceId, deleted, lastSyncedAt, embeddedFigures, " +
            "imageHref, imageSvg, imageBytes, originFontFamily, emphasisStyles, textSnippetHtml, fragmentAnchor"
        private const val PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
    }
}
