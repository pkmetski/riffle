package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

@Suppress("TooManyFunctions")
internal class IosLibraryItemDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LibraryItemDao {
    override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(queryByLibraryId(sourceId, libraryId)) }
        }

    override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> =
        queryByLibraryId(sourceId, libraryId)

    override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    "SELECT $ALL_COLS FROM library_items WHERE sourceId = ?",
                    ::mapRows, 1,
                ) { bindString(0, sourceId) }.value)
            }
        }

    override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $ALL_COLS FROM library_items
                       WHERE sourceId = ? AND libraryId = ?
                       AND id NOT IN (
                           SELECT itemId FROM series_items
                           WHERE sourceId = ?
                             AND seriesId IN (SELECT id FROM series WHERE libraryId = ?)
                       )
                       AND id NOT IN (
                           SELECT itemId FROM collection_items
                           WHERE sourceId = ?
                             AND collectionId IN (SELECT id FROM collections WHERE libraryId = ?)
                       )
                       ORDER BY title ASC""",
                    ::mapRows, 6,
                ) {
                    bindString(0, sourceId); bindString(1, libraryId)
                    bindString(2, sourceId); bindString(3, libraryId)
                    bindString(4, sourceId); bindString(5, libraryId)
                }.value)
            }
        }

    override suspend fun upsertAll(items: List<LibraryItemEntity>) {
        items.forEach { insertOrReplaceItem(it) }
        if (items.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) {
        items.forEach { item ->
            driver.execute(null, "INSERT OR IGNORE INTO library_items ($ALL_COLS) VALUES ($PLACEHOLDERS)", 24) {
                bindItem(item)
            }
        }
        if (items.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun updateMetadata(metadata: LibraryItemMetadata) {
        driver.execute(null, """UPDATE library_items SET
            libraryId = ?, title = ?, author = ?, coverUrl = ?,
            ebookFileIno = ?, ebookFormat = ?, hasAudio = ?,
            audioDurationSec = ?, description = ?, seriesName = ?,
            publishedYear = ?, genres = ?, publisher = ?, language = ?,
            lastOpenedAt = ?, addedAt = ?, isbn = ?, asin = ?,
            finishedAt = ?, pageCount = ?
            WHERE sourceId = ? AND id = ?""", 22) {
            bindString(0, metadata.libraryId)
            bindString(1, metadata.title)
            bindString(2, metadata.author)
            bindString(3, metadata.coverUrl)
            bindString(4, metadata.ebookFileIno)
            bindString(5, metadata.ebookFormat)
            bindLong(6, if (metadata.hasAudio) 1L else 0L)
            bindDouble(7, metadata.audioDurationSec)
            bindString(8, metadata.description)
            bindString(9, metadata.seriesName)
            bindString(10, metadata.publishedYear)
            bindString(11, metadata.genres)
            bindString(12, metadata.publisher)
            bindString(13, metadata.language)
            bindLong(14, metadata.lastOpenedAt)
            bindLong(15, metadata.addedAt)
            bindString(16, metadata.isbn)
            bindString(17, metadata.asin)
            bindLong(18, metadata.finishedAt)
            bindLong(19, metadata.pageCount?.toLong())
            bindString(20, metadata.sourceId)
            bindString(21, metadata.id)
        }
        invalidator.invalidate()
    }

    override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val placeholders = itemIds.joinToString(",") { "?" }
        driver.execute(null, "DELETE FROM library_items WHERE sourceId = ? AND id IN ($placeholders)",
            1 + itemIds.size) {
            bindString(0, sourceId)
            itemIds.forEachIndexed { i, id -> bindString(i + 1, id) }
        }
        invalidator.invalidate()
    }

    override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> =
        driver.executeQuery(
            null,
            "SELECT id FROM library_items WHERE sourceId = ? AND libraryId = ?",
            { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) ids.add(cursor.getString(0)!!)
                QueryResult.Value(ids)
            },
            2,
        ) { bindString(0, sourceId); bindString(1, libraryId) }.value

    override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM library_items WHERE sourceId = ? AND id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toLibraryItemEntity() else null) },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> {
        if (itemIds.isEmpty()) return emptyList()
        val placeholders = itemIds.joinToString(",") { "?" }
        return driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM library_items WHERE sourceId = ? AND id IN ($placeholders)",
            ::mapRows,
            1 + itemIds.size,
        ) {
            bindString(0, sourceId)
            itemIds.forEachIndexed { i, id -> bindString(i + 1, id) }
        }.value
    }

    override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> =
        invalidator.version.flatMapLatest { flow { emit(getById(sourceId, itemId)) } }

    override suspend fun findSourceIdForItem(itemId: String): String? =
        driver.executeQuery(
            null,
            "SELECT sourceId FROM library_items WHERE id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            1,
        ) { bindString(0, itemId) }.value

    override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) {
        driver.execute(null, "DELETE FROM library_items WHERE sourceId = ? AND libraryId = ?", 2) {
            bindString(0, sourceId); bindString(1, libraryId)
        }
        invalidator.invalidate()
    }

    override suspend fun deleteById(sourceId: String, itemId: String) {
        driver.execute(null, "DELETE FROM library_items WHERE sourceId = ? AND id = ?", 2) {
            bindString(0, sourceId); bindString(1, itemId)
        }
        invalidator.invalidate()
    }

    override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $ALL_COLS FROM library_items
                       WHERE sourceId = ? AND libraryId = ?
                         AND readingProgress > 0.0 AND readingProgress < 0.99
                       ORDER BY lastOpenedAt IS NULL ASC, lastOpenedAt DESC""",
                    ::mapRows, 2,
                ) { bindString(0, sourceId); bindString(1, libraryId) }.value)
            }
        }

    override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $ALL_COLS FROM library_items
                       WHERE sourceId = ? AND libraryId = ? AND readingProgress >= 0.99
                       ORDER BY COALESCE(finishedAt, lastOpenedAt) IS NULL ASC,
                                COALESCE(finishedAt, lastOpenedAt) DESC""",
                    ::mapRows, 2,
                ) { bindString(0, sourceId); bindString(1, libraryId) }.value)
            }
        }

    override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    "SELECT $ALL_COLS FROM library_items WHERE sourceId = ? AND libraryId = ? AND addedAt > 0 ORDER BY addedAt DESC",
                    ::mapRows, 2,
                ) { bindString(0, sourceId); bindString(1, libraryId) }.value)
            }
        }

    override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    "SELECT $ALL_COLS FROM library_items WHERE sourceId = ? AND libraryId = ? ORDER BY title ASC",
                    ::mapRows, 2,
                ) { bindString(0, sourceId); bindString(1, libraryId) }.value)
            }
        }

    override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) {
        driver.execute(null,
            "UPDATE library_items SET lastOpenedAt = ?, addedAt = CASE WHEN addedAt = 0 THEN ? ELSE addedAt END WHERE sourceId = ? AND id = ?",
            4) {
            bindLong(0, timestamp); bindLong(1, timestamp)
            bindString(2, sourceId); bindString(3, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
        driver.execute(null,
            "UPDATE library_items SET readingProgress = ? WHERE sourceId = ? AND id = ?", 3) {
            bindDouble(0, progress.toDouble())
            bindString(1, sourceId); bindString(2, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) {
        driver.execute(null,
            "UPDATE library_items SET libraryId = ? WHERE sourceId = ? AND id = ?", 3) {
            bindString(0, libraryId); bindString(1, sourceId); bindString(2, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {
        driver.execute(null,
            "UPDATE library_items SET finishedAt = ? WHERE sourceId = ? AND id = ?", 3) {
            bindLong(0, finishedAt); bindString(1, sourceId); bindString(2, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> =
        driver.executeQuery(
            null,
            "SELECT id, lastOpenedAt FROM library_items WHERE sourceId = ? AND libraryId = ? AND lastOpenedAt IS NOT NULL",
            { cursor ->
                val rows = mutableListOf<LastOpenedAtRow>()
                while (cursor.next().value) rows.add(LastOpenedAtRow(cursor.getString(0)!!, cursor.getLong(1)!!))
                QueryResult.Value(rows)
            },
            2,
        ) { bindString(0, sourceId); bindString(1, libraryId) }.value

    override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> =
        driver.executeQuery(
            null,
            "SELECT id, readingProgress FROM library_items WHERE sourceId = ? AND libraryId = ? AND readingProgress > 0.0",
            { cursor ->
                val rows = mutableListOf<ReadingProgressRow>()
                while (cursor.next().value) rows.add(ReadingProgressRow(cursor.getString(0)!!, cursor.getDouble(1)!!.toFloat()))
                QueryResult.Value(rows)
            },
            2,
        ) { bindString(0, sourceId); bindString(1, libraryId) }.value

    override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> =
        driver.executeQuery(
            null,
            """SELECT li.id AS itemId, li.sourceId AS sourceId, li.title, li.author, li.isbn, li.asin
               FROM library_items li
               JOIN sources s ON li.sourceId = s.id
               WHERE s.serverType = ?""",
            { cursor ->
                val rows = mutableListOf<MatchableItemRow>()
                while (cursor.next().value) rows.add(MatchableItemRow(
                    itemId = cursor.getString(0)!!,
                    sourceId = cursor.getString(1)!!,
                    title = cursor.getString(2)!!,
                    author = cursor.getString(3)!!,
                    isbn = cursor.getString(4),
                    asin = cursor.getString(5),
                ))
                QueryResult.Value(rows)
            },
            1,
        ) { bindString(0, serverType) }.value

    private fun queryByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM library_items WHERE sourceId = ? AND libraryId = ? ORDER BY title ASC",
            ::mapRows,
            2,
        ) { bindString(0, sourceId); bindString(1, libraryId) }.value

    private fun insertOrReplaceItem(item: LibraryItemEntity) {
        driver.execute(null, "INSERT OR REPLACE INTO library_items ($ALL_COLS) VALUES ($PLACEHOLDERS)", 24) {
            bindItem(item)
        }
    }

    private fun app.cash.sqldelight.db.SqlPreparedStatement.bindItem(item: LibraryItemEntity) {
        bindString(0, item.sourceId)
        bindString(1, item.id)
        bindString(2, item.libraryId)
        bindString(3, item.title)
        bindString(4, item.author)
        bindString(5, item.coverUrl)
        bindDouble(6, item.readingProgress.toDouble())
        bindString(7, item.ebookFileIno)
        bindString(8, item.ebookFormat)
        bindLong(9, if (item.hasAudio) 1L else 0L)
        bindDouble(10, item.audioDurationSec)
        bindString(11, item.description)
        bindString(12, item.seriesName)
        bindString(13, item.seriesSequence)
        bindString(14, item.publishedYear)
        bindString(15, item.genres)
        bindString(16, item.publisher)
        bindString(17, item.language)
        bindLong(18, item.lastOpenedAt)
        bindLong(19, item.addedAt)
        bindString(20, item.isbn)
        bindString(21, item.asin)
        bindLong(22, item.finishedAt)
        bindLong(23, item.pageCount?.toLong())
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<LibraryItemEntity>> {
        val result = mutableListOf<LibraryItemEntity>()
        while (cursor.next().value) result.add(cursor.toLibraryItemEntity())
        return QueryResult.Value(result)
    }

    private fun SqlCursor.toLibraryItemEntity() = LibraryItemEntity(
        sourceId = getString(0)!!,
        id = getString(1)!!,
        libraryId = getString(2)!!,
        title = getString(3)!!,
        author = getString(4)!!,
        coverUrl = getString(5),
        readingProgress = getDouble(6)?.toFloat() ?: 0f,
        ebookFileIno = getString(7),
        ebookFormat = getString(8) ?: "unsupported",
        hasAudio = getLong(9)?.let { it != 0L } ?: false,
        audioDurationSec = getDouble(10) ?: 0.0,
        description = getString(11),
        seriesName = getString(12),
        seriesSequence = getString(13),
        publishedYear = getString(14),
        genres = getString(15) ?: "",
        publisher = getString(16),
        language = getString(17),
        lastOpenedAt = getLong(18),
        addedAt = getLong(19)!!,
        isbn = getString(20),
        asin = getString(21),
        finishedAt = getLong(22),
        pageCount = getLong(23)?.toInt(),
    )

    companion object {
        private const val ALL_COLS = "sourceId, id, libraryId, title, author, coverUrl, readingProgress, " +
            "ebookFileIno, ebookFormat, hasAudio, audioDurationSec, description, seriesName, seriesSequence, " +
            "publishedYear, genres, publisher, language, lastOpenedAt, addedAt, isbn, asin, finishedAt, pageCount"
        private const val PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
    }
}
