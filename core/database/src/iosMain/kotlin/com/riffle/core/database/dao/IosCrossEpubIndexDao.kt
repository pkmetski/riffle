package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.CrossEpubIndexEntity
import com.riffle.core.database.IosInvalidator

internal class IosCrossEpubIndexDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : CrossEpubIndexDao {

    override suspend fun upsert(entity: CrossEpubIndexEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO cross_epub_index (absEpubChecksum, storytellerEpubChecksum, perChapterMapsBlob, builtAt) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, entity.absEpubChecksum)
            bindString(1, entity.storytellerEpubChecksum)
            bindString(2, entity.perChapterMapsBlob)
            bindLong(3, entity.builtAt)
        }
        invalidator.invalidate()
    }

    override suspend fun find(absEpubChecksum: String, storytellerEpubChecksum: String): CrossEpubIndexEntity? =
        driver.executeQuery(
            null,
            "SELECT absEpubChecksum, storytellerEpubChecksum, perChapterMapsBlob, builtAt FROM cross_epub_index " +
                "WHERE absEpubChecksum = ? AND storytellerEpubChecksum = ? LIMIT 1",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        CrossEpubIndexEntity(
                            absEpubChecksum = cursor.getString(0)!!,
                            storytellerEpubChecksum = cursor.getString(1)!!,
                            perChapterMapsBlob = cursor.getString(2)!!,
                            builtAt = cursor.getLong(3)!!,
                        )
                    } else {
                        null
                    },
                )
            },
            2,
        ) { bindString(0, absEpubChecksum); bindString(1, storytellerEpubChecksum) }.value

    override suspend fun clear() {
        driver.execute(null, "DELETE FROM cross_epub_index", 0)
        invalidator.invalidate()
    }
}
