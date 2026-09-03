package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.TocCacheDao
import com.riffle.core.database.TocCacheEntity

internal class IosTocCacheDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : TocCacheDao {
    override suspend fun get(sourceId: String, itemId: String): TocCacheEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, ebookFileIno, entriesJson, cachedAt FROM toc_cache WHERE sourceId = ? AND itemId = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        TocCacheEntity(
                            sourceId = cursor.getString(0)!!,
                            itemId = cursor.getString(1)!!,
                            ebookFileIno = cursor.getString(2)!!,
                            entriesJson = cursor.getString(3)!!,
                            cachedAt = cursor.getLong(4)!!,
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun upsert(entity: TocCacheEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO toc_cache (sourceId, itemId, ebookFileIno, entriesJson, cachedAt) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.ebookFileIno)
            bindString(3, entity.entriesJson)
            bindLong(4, entity.cachedAt)
        }
        invalidator.invalidate()
    }
}
