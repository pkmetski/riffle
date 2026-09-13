package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity

@Suppress("TooManyFunctions")
internal class IosReadingPositionDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : ReadingPositionDao {

    override suspend fun upsert(entity: ReadingPositionEntity) {
        driver.execute(
            null,
            """INSERT OR REPLACE INTO reading_positions
               (sourceId, itemId, cfi, localUpdatedAt, lastSyncedAt, deleted)
               VALUES (?, ?, ?, ?, ?, ?)""",
            6,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindString(2, entity.cfi)
            bindLong(3, entity.localUpdatedAt)
            bindLong(4, entity.lastSyncedAt)
            bindLong(5, if (entity.deleted) 1L else 0L)
        }
        invalidator.invalidate()
    }

    override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, cfi, localUpdatedAt, lastSyncedAt, deleted FROM reading_positions WHERE sourceId = ? AND itemId = ? LIMIT 1",
            ::mapRows,
            2,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
        }.value.firstOrNull()

    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
        driver.execute(
            null,
            "UPDATE reading_positions SET localUpdatedAt = ? WHERE sourceId = ? AND itemId = ?",
            3,
        ) {
            bindLong(0, millis)
            bindString(1, sourceId)
            bindString(2, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun acceptServerIfUnchanged(
        sourceId: String,
        itemId: String,
        position: String,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
        deleted: Boolean,
    ): Int {
        val rows = driver.execute(
            null,
            """UPDATE reading_positions
               SET cfi = ?, deleted = ?, localUpdatedAt = ?, lastSyncedAt = ?
               WHERE sourceId = ? AND itemId = ? AND localUpdatedAt = ?""",
            7,
        ) {
            bindString(0, position)
            bindLong(1, if (deleted) 1L else 0L)
            bindLong(2, serverStamp)
            bindLong(3, serverStamp)
            bindString(4, sourceId)
            bindString(5, itemId)
            bindLong(6, ifLocalUpdatedAt)
        }.value.toInt()
        if (rows > 0) {
            invalidator.invalidate()
            return rows
        }
        // Row absent → insert fresh (server wins on first open).
        if (getByItemId(sourceId, itemId) == null) {
            upsert(ReadingPositionEntity(sourceId, itemId, position, serverStamp, serverStamp, deleted))
            return 1
        }
        return 0
    }

    override suspend fun confirmPushedIfUnchanged(
        sourceId: String,
        itemId: String,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
    ): Int {
        val rows = driver.execute(
            null,
            """UPDATE reading_positions SET localUpdatedAt = ?, lastSyncedAt = ?
               WHERE sourceId = ? AND itemId = ? AND localUpdatedAt = ?""",
            5,
        ) {
            bindLong(0, serverStamp)
            bindLong(1, serverStamp)
            bindString(2, sourceId)
            bindString(3, itemId)
            bindLong(4, ifLocalUpdatedAt)
        }.value.toInt()
        if (rows > 0) invalidator.invalidate()
        return rows
    }

    override suspend fun confirmInSyncIfUnchanged(
        sourceId: String,
        itemId: String,
        ifLocalUpdatedAt: Long,
    ): Int {
        val rows = driver.execute(
            null,
            """UPDATE reading_positions SET lastSyncedAt = localUpdatedAt
               WHERE sourceId = ? AND itemId = ? AND localUpdatedAt = ?""",
            3,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
            bindLong(2, ifLocalUpdatedAt)
        }.value.toInt()
        if (rows > 0) invalidator.invalidate()
        return rows
    }

    override suspend fun dirtyForSource(sourceId: String): List<ReadingPositionEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, cfi, localUpdatedAt, lastSyncedAt, deleted FROM reading_positions WHERE sourceId = ? AND localUpdatedAt > lastSyncedAt",
            ::mapRows,
            1,
        ) { bindString(0, sourceId) }.value

    override suspend fun sourcesWithDirtyRows(): List<String> =
        driver.executeQuery(
            null,
            "SELECT DISTINCT sourceId FROM reading_positions WHERE localUpdatedAt > lastSyncedAt",
            { cursor ->
                val result = mutableListOf<String>()
                while (cursor.next().value) result += cursor.getString(0)!!
                QueryResult.Value(result)
            },
            0,
        ).value

    override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) {
        driver.execute(
            null,
            "UPDATE reading_positions SET deleted = 1, localUpdatedAt = ? WHERE sourceId = ? AND itemId = ?",
            3,
        ) {
            bindLong(0, localUpdatedAt)
            bindString(1, sourceId)
            bindString(2, itemId)
        }
        invalidator.invalidate()
    }

    override suspend fun acceptServerDeletionIfUnchanged(
        sourceId: String,
        itemId: String,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
    ): Int {
        val rows = driver.execute(
            null,
            """UPDATE reading_positions
               SET deleted = 1, localUpdatedAt = ?, lastSyncedAt = ?
               WHERE sourceId = ? AND itemId = ? AND localUpdatedAt = ?""",
            5,
        ) {
            bindLong(0, serverStamp)
            bindLong(1, serverStamp)
            bindString(2, sourceId)
            bindString(3, itemId)
            bindLong(4, ifLocalUpdatedAt)
        }.value.toInt()
        if (rows > 0) invalidator.invalidate()
        return rows
    }

    override suspend fun allForSource(sourceId: String): List<ReadingPositionEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, cfi, localUpdatedAt, lastSyncedAt, deleted FROM reading_positions WHERE sourceId = ?",
            ::mapRows,
            1,
        ) { bindString(0, sourceId) }.value

    private fun mapRows(cursor: SqlCursor): QueryResult<List<ReadingPositionEntity>> {
        val result = mutableListOf<ReadingPositionEntity>()
        while (cursor.next().value) {
            result += ReadingPositionEntity(
                sourceId = cursor.getString(0)!!,
                itemId = cursor.getString(1)!!,
                cfi = cursor.getString(2)!!,
                localUpdatedAt = cursor.getLong(3)!!,
                lastSyncedAt = cursor.getLong(4)!!,
                deleted = cursor.getLong(5) == 1L,
            )
        }
        return QueryResult.Value(result)
    }
}
