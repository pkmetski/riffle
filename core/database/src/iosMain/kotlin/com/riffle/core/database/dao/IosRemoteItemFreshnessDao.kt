package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RemoteItemFreshnessEntity

internal class IosRemoteItemFreshnessDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : RemoteItemFreshnessDao {

    override suspend fun upsert(entity: RemoteItemFreshnessEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO remote_item_freshness (sourceId, sourceItemId, lastFetchedAt) VALUES (?, ?, ?)",
            3,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.sourceItemId)
            bindLong(2, entity.lastFetchedAt)
        }
        invalidator.invalidate()
    }

    override suspend fun lastFetchedAt(sourceId: String, sourceItemId: String): Long? =
        driver.executeQuery(
            null,
            "SELECT lastFetchedAt FROM remote_item_freshness WHERE sourceId = ? AND sourceItemId = ?",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }.value

    override suspend fun clear(sourceId: String, sourceItemId: String) {
        driver.execute(
            null,
            "DELETE FROM remote_item_freshness WHERE sourceId = ? AND sourceItemId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }
        invalidator.invalidate()
    }
}
