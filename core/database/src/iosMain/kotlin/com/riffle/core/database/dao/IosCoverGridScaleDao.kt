package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.CoverGridScaleDao
import com.riffle.core.database.CoverGridScaleEntity
import com.riffle.core.database.IosInvalidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosCoverGridScaleDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : CoverGridScaleDao {

    override suspend fun upsert(entity: CoverGridScaleEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO cover_grid_scale (sourceId, libraryId, screenDimensionBucket, scale) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.libraryId)
            bindString(2, entity.screenDimensionBucket)
            bindDouble(3, entity.scale.toDouble())
        }
        invalidator.invalidate()
    }

    override fun observeScale(sourceId: String, libraryId: String, bucket: String): Flow<Float?> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT scale FROM cover_grid_scale WHERE sourceId = ? AND libraryId = ? AND screenDimensionBucket = ?",
                        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getDouble(0)?.toFloat() else null) },
                        3,
                    ) { bindString(0, sourceId); bindString(1, libraryId); bindString(2, bucket) }.value,
                )
            }
        }
}
