package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosSeriesDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : SeriesDao {

    override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(queryByLibraryId(libraryId)) }
        }

    override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $LI_COLS FROM library_items li
                       INNER JOIN series_items si ON li.sourceId = si.sourceId AND li.id = si.itemId
                       WHERE si.sourceId = ? AND si.seriesId = ?
                       ORDER BY si.sequenceOrder ASC""",
                    ::mapLibraryItemRows, 2,
                ) { bindString(0, sourceId); bindString(1, seriesId) }.value)
            }
        }

    override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $LI_COLS FROM library_items li
                       WHERE li.sourceId = ? AND li.libraryId = ?
                         AND li.readingProgress < 0.99
                         AND EXISTS (
                             SELECT 1 FROM series_items si
                             WHERE si.sourceId = li.sourceId
                               AND si.itemId = li.id
                               AND si.seriesId IN (
                                   SELECT DISTINCT si2.seriesId
                                   FROM series_items si2
                                   INNER JOIN library_items li2 ON li2.sourceId = si2.sourceId AND li2.id = si2.itemId
                                   WHERE li2.sourceId = ? AND li2.libraryId = ? AND li2.readingProgress >= 0.99
                               )
                               AND si.seriesId NOT IN (
                                   SELECT DISTINCT si5.seriesId
                                   FROM series_items si5
                                   INNER JOIN library_items li5 ON li5.sourceId = si5.sourceId AND li5.id = si5.itemId
                                   WHERE li5.sourceId = ? AND li5.libraryId = ?
                                     AND li5.readingProgress >= 0.05
                                     AND li5.readingProgress < 0.99
                               )
                               AND si.sequenceOrder = (
                                   SELECT MIN(si3.sequenceOrder)
                                   FROM series_items si3
                                   INNER JOIN library_items li3 ON li3.sourceId = si3.sourceId AND li3.id = si3.itemId
                                   WHERE si3.seriesId = si.seriesId
                                     AND li3.sourceId = ? AND li3.libraryId = ?
                                     AND li3.readingProgress < 0.99
                               )
                         )
                       ORDER BY COALESCE(
                           (
                               SELECT MAX(li4.lastOpenedAt)
                               FROM series_items si4
                               INNER JOIN library_items li4 ON li4.sourceId = si4.sourceId AND li4.id = si4.itemId
                               WHERE si4.seriesId IN (
                                   SELECT si_self.seriesId FROM series_items si_self
                                   WHERE si_self.sourceId = li.sourceId AND si_self.itemId = li.id
                               )
                                 AND li4.sourceId = ? AND li4.libraryId = ?
                                 AND li4.readingProgress >= 0.99
                           ), 0
                       ) DESC""",
                    ::mapLibraryItemRows, 10,
                ) {
                    bindString(0, sourceId); bindString(1, libraryId)
                    bindString(2, sourceId); bindString(3, libraryId)
                    bindString(4, sourceId); bindString(5, libraryId)
                    bindString(6, sourceId); bindString(7, libraryId)
                    bindString(8, sourceId); bindString(9, libraryId)
                }.value)
            }
        }

    override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? =
        driver.executeQuery(
            null,
            "SELECT seriesId FROM series_items WHERE sourceId = ? AND itemId = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun upsertAll(series: List<SeriesEntity>) {
        series.forEach { s ->
            driver.execute(
                null,
                "INSERT OR REPLACE INTO series (id, libraryId, name, coverUrl, bookCount) VALUES (?, ?, ?, ?, ?)",
                5,
            ) {
                bindString(0, s.id)
                bindString(1, s.libraryId)
                bindString(2, s.name)
                bindString(3, s.coverUrl)
                bindLong(4, s.bookCount.toLong())
            }
        }
        if (series.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun upsertAllItems(items: List<SeriesItemEntity>) {
        items.forEach { item ->
            driver.execute(
                null,
                "INSERT OR REPLACE INTO series_items (seriesId, sourceId, itemId, sequenceOrder) VALUES (?, ?, ?, ?)",
                4,
            ) {
                bindString(0, item.seriesId)
                bindString(1, item.sourceId)
                bindString(2, item.itemId)
                bindDouble(3, item.sequenceOrder.toDouble())
            }
        }
        if (items.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun deleteByLibraryId(libraryId: String) {
        driver.execute(null, "DELETE FROM series WHERE libraryId = ?", 1) {
            bindString(0, libraryId)
        }
        invalidator.invalidate()
    }

    override suspend fun deleteItemsByLibraryId(libraryId: String) {
        driver.execute(
            null,
            "DELETE FROM series_items WHERE seriesId IN (SELECT id FROM series WHERE libraryId = ?)",
            1,
        ) { bindString(0, libraryId) }
    }

    private fun queryByLibraryId(libraryId: String): List<SeriesEntity> =
        driver.executeQuery(
            null,
            "SELECT id, libraryId, name, coverUrl, bookCount FROM series WHERE libraryId = ? ORDER BY name ASC",
            { cursor ->
                val result = mutableListOf<SeriesEntity>()
                while (cursor.next().value) result.add(cursor.toSeriesEntity())
                QueryResult.Value(result)
            },
            1,
        ) { bindString(0, libraryId) }.value

    private fun SqlCursor.toSeriesEntity() = SeriesEntity(
        id = getString(0)!!,
        libraryId = getString(1)!!,
        name = getString(2)!!,
        coverUrl = getString(3),
        bookCount = getLong(4)!!.toInt(),
    )

    private fun mapLibraryItemRows(cursor: SqlCursor): QueryResult.Value<List<LibraryItemEntity>> {
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
        ebookFormat = getString(8)!!,
        hasAudio = getLong(9)!! != 0L,
        audioDurationSec = getDouble(10)!!,
        description = getString(11),
        seriesName = getString(12),
        seriesSequence = getString(13),
        publishedYear = getString(14),
        genres = getString(15)!!,
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
        private const val LI_COLS =
            "li.sourceId, li.id, li.libraryId, li.title, li.author, li.coverUrl, " +
            "li.readingProgress, li.ebookFileIno, li.ebookFormat, li.hasAudio, li.audioDurationSec, " +
            "li.description, li.seriesName, li.seriesSequence, li.publishedYear, li.genres, li.publisher, " +
            "li.language, li.lastOpenedAt, li.addedAt, li.isbn, li.asin, li.finishedAt, li.pageCount"
    }
}
