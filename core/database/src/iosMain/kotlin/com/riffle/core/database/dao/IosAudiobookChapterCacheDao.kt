package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.database.IosInvalidator

internal class IosAudiobookChapterCacheDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : AudiobookChapterCacheDao {

    override suspend fun get(sourceId: String, itemId: String): AudiobookChapterCacheEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, chaptersJson, cachedAt FROM audiobook_chapter_cache WHERE sourceId = ? AND itemId = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        AudiobookChapterCacheEntity(
                            sourceId = cursor.getString(0)!!,
                            itemId = cursor.getString(1)!!,
                            chaptersJson = cursor.getString(2)!!,
                            cachedAt = cursor.getLong(3)!!,
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun upsert(entity: AudiobookChapterCacheEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO audiobook_chapter_cache (sourceId, itemId, chaptersJson, cachedAt) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.chaptersJson)
            bindLong(3, entity.cachedAt)
        }
        invalidator.invalidate()
    }
}
