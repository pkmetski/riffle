package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.BookComicFormattingPreferencesEntity
import com.riffle.core.database.IosInvalidator

internal class IosBookComicFormattingPreferencesDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : BookComicFormattingPreferencesDao {

    override suspend fun upsert(entity: BookComicFormattingPreferencesEntity) {
        driver.execute(
            null,
            """INSERT OR REPLACE INTO book_comic_formatting_preferences
               (source_id, item_id, background_theme, panel_view_on, panel_overflow, panel_animation_speed_ms)
               VALUES (?, ?, ?, ?, ?, ?)""",
            6,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.backgroundTheme)
            bindLong(3, entity.panelViewOn?.let { if (it) 1L else 0L })
            bindString(4, entity.panelOverflow)
            bindLong(5, entity.panelAnimationSpeedMs?.toLong())
        }
        invalidator.invalidate()
    }

    override suspend fun getByItemId(
        sourceId: String,
        itemId: String,
    ): BookComicFormattingPreferencesEntity? =
        driver.executeQuery(
            null,
            """SELECT source_id, item_id, background_theme, panel_view_on, panel_overflow, panel_animation_speed_ms
               FROM book_comic_formatting_preferences
               WHERE source_id = ? AND item_id = ? LIMIT 1""",
            ::mapRows,
            2,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
        }.value.firstOrNull()

    override suspend fun deleteByItemId(sourceId: String, itemId: String) {
        driver.execute(
            null,
            "DELETE FROM book_comic_formatting_preferences WHERE source_id = ? AND item_id = ?",
            2,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
        }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult<List<BookComicFormattingPreferencesEntity>> {
        val result = mutableListOf<BookComicFormattingPreferencesEntity>()
        while (cursor.next().value) {
            result += BookComicFormattingPreferencesEntity(
                sourceId = cursor.getString(0)!!,
                itemId = cursor.getString(1)!!,
                backgroundTheme = cursor.getString(2),
                panelViewOn = cursor.getLong(3)?.let { it == 1L },
                panelOverflow = cursor.getString(4),
                panelAnimationSpeedMs = cursor.getLong(5)?.toInt(),
            )
        }
        return QueryResult.Value(result)
    }
}
