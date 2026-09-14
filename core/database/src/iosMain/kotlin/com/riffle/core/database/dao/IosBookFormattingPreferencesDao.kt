package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.database.IosInvalidator

internal class IosBookFormattingPreferencesDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : BookFormattingPreferencesDao {

    override suspend fun upsert(entity: BookFormattingPreferencesEntity) {
        driver.execute(
            null,
            """INSERT OR REPLACE INTO book_formatting_preferences
               (sourceId, itemId, screenDimensionBucket, fontSize, theme, fontFamily,
                lineSpacing, margins, orientation, showChapterMap, coloredChapterMap,
                showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread,
                justifyText, showReadingTimeEstimate)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            16,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.screenDimensionBucket)
            bindDouble(3, entity.fontSize?.toDouble())
            bindString(4, entity.theme)
            bindString(5, entity.fontFamily)
            bindDouble(6, entity.lineSpacing?.toDouble())
            bindDouble(7, entity.margins?.toDouble())
            bindString(8, entity.orientation)
            bindLong(9, entity.showChapterMap?.let { if (it) 1L else 0L })
            bindLong(10, entity.coloredChapterMap?.let { if (it) 1L else 0L })
            bindLong(11, entity.showReadingProgressLabels?.let { if (it) 1L else 0L })
            bindLong(12, entity.showCurrentChapterLabel?.let { if (it) 1L else 0L })
            bindLong(13, entity.doublePageSpread?.let { if (it) 1L else 0L })
            bindLong(14, entity.justifyText?.let { if (it) 1L else 0L })
            bindLong(15, entity.showReadingTimeEstimate?.let { if (it) 1L else 0L })
        }
        invalidator.invalidate()
    }

    override suspend fun getByItemId(
        sourceId: String,
        itemId: String,
        screenDimensionBucket: String,
    ): BookFormattingPreferencesEntity? =
        driver.executeQuery(
            null,
            """SELECT sourceId, itemId, screenDimensionBucket, fontSize, theme, fontFamily,
                      lineSpacing, margins, orientation, showChapterMap, coloredChapterMap,
                      showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread,
                      justifyText, showReadingTimeEstimate
               FROM book_formatting_preferences
               WHERE sourceId = ? AND itemId = ? AND screenDimensionBucket = ? LIMIT 1""",
            ::mapRows,
            3,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
            bindString(2, screenDimensionBucket)
        }.value.firstOrNull()

    override suspend fun deleteByItemId(
        sourceId: String,
        itemId: String,
        screenDimensionBucket: String,
    ) {
        driver.execute(
            null,
            "DELETE FROM book_formatting_preferences WHERE sourceId = ? AND itemId = ? AND screenDimensionBucket = ?",
            3,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
            bindString(2, screenDimensionBucket)
        }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult<List<BookFormattingPreferencesEntity>> {
        val result = mutableListOf<BookFormattingPreferencesEntity>()
        while (cursor.next().value) {
            result += BookFormattingPreferencesEntity(
                sourceId = cursor.getString(0)!!,
                itemId = cursor.getString(1)!!,
                screenDimensionBucket = cursor.getString(2)!!,
                fontSize = cursor.getDouble(3)?.toFloat(),
                theme = cursor.getString(4),
                fontFamily = cursor.getString(5),
                lineSpacing = cursor.getDouble(6)?.toFloat(),
                margins = cursor.getDouble(7)?.toFloat(),
                orientation = cursor.getString(8),
                showChapterMap = cursor.getLong(9)?.let { it == 1L },
                coloredChapterMap = cursor.getLong(10)?.let { it == 1L },
                showReadingProgressLabels = cursor.getLong(11)?.let { it == 1L },
                showCurrentChapterLabel = cursor.getLong(12)?.let { it == 1L },
                doublePageSpread = cursor.getLong(13)?.let { it == 1L },
                justifyText = cursor.getLong(14)?.let { it == 1L },
                showReadingTimeEstimate = cursor.getLong(15)?.let { it == 1L },
            )
        }
        return QueryResult.Value(result)
    }
}
