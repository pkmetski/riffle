package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadaloudResumePositionEntity

internal class IosReadaloudResumePositionDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : ReadaloudResumePositionDao {

    override suspend fun upsert(entity: ReadaloudResumePositionEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO readaloud_resume_positions (sourceId, itemId, href, progression, fragmentRef, localUpdatedAt) VALUES (?, ?, ?, ?, ?, ?)",
            6,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.href)
            bindDouble(3, entity.progression)
            bindString(4, entity.fragmentRef)
            bindLong(5, entity.localUpdatedAt)
        }
        invalidator.invalidate()
    }

    override suspend fun getByItemId(sourceId: String, itemId: String): ReadaloudResumePositionEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, href, progression, fragmentRef, localUpdatedAt FROM readaloud_resume_positions " +
                "WHERE sourceId = ? AND itemId = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        ReadaloudResumePositionEntity(
                            sourceId = cursor.getString(0)!!,
                            itemId = cursor.getString(1)!!,
                            href = cursor.getString(2)!!,
                            progression = cursor.getDouble(3),
                            fragmentRef = cursor.getString(4),
                            localUpdatedAt = cursor.getLong(5) ?: 0L,
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun deleteByItemId(sourceId: String, itemId: String) {
        driver.execute(
            null,
            "DELETE FROM readaloud_resume_positions WHERE sourceId = ? AND itemId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }
        invalidator.invalidate()
    }
}
