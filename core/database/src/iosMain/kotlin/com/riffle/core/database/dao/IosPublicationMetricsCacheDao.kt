package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity

private const val ALL_COLS = "sourceId, itemId, ebookFileIno, totalPositions, pageCount, cachedAt, epubVersion"

internal class IosPublicationMetricsCacheDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : PublicationMetricsCacheDao {

    override suspend fun get(sourceId: String, itemId: String): PublicationMetricsCacheEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM publication_metrics_cache WHERE sourceId = ? AND itemId = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        PublicationMetricsCacheEntity(
                            sourceId = cursor.getString(0)!!,
                            itemId = cursor.getString(1)!!,
                            ebookFileIno = cursor.getString(2)!!,
                            totalPositions = cursor.getLong(3)?.toInt(),
                            pageCount = cursor.getLong(4)?.toInt(),
                            cachedAt = cursor.getLong(5)!!,
                            epubVersion = cursor.getString(6),
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun upsert(entity: PublicationMetricsCacheEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO publication_metrics_cache ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?)",
            7,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.ebookFileIno)
            bindLong(3, entity.totalPositions?.toLong())
            bindLong(4, entity.pageCount?.toLong())
            bindLong(5, entity.cachedAt)
            bindString(6, entity.epubVersion)
        }
        invalidator.invalidate()
    }
}
