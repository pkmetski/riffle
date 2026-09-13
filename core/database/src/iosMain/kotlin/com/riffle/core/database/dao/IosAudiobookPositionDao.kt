package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.IosInvalidator

@Suppress("TooManyFunctions")
internal class IosAudiobookPositionDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : AudiobookPositionDao {

    override suspend fun upsert(entity: AudiobookPositionEntity) {
        driver.execute(
            null,
            """INSERT OR REPLACE INTO audiobook_positions
               (sourceId, itemId, positionSec, localUpdatedAt, lastSyncedAt, deleted)
               VALUES (?, ?, ?, ?, ?, ?)""",
            6,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.itemId)
            bindDouble(2, entity.positionSec)
            bindLong(3, entity.localUpdatedAt)
            bindLong(4, entity.lastSyncedAt)
            bindLong(5, if (entity.deleted) 1L else 0L)
        }
        invalidator.invalidate()
    }

    override suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, positionSec, localUpdatedAt, lastSyncedAt, deleted FROM audiobook_positions WHERE sourceId = ? AND itemId = ? LIMIT 1",
            ::mapRows,
            2,
        ) {
            bindString(0, sourceId)
            bindString(1, itemId)
        }.value.firstOrNull()

    override suspend fun acceptServerIfUnchanged(
        sourceId: String,
        itemId: String,
        positionSec: Double,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
        deleted: Boolean,
    ): Int {
        val rows = driver.execute(
            null,
            """UPDATE audiobook_positions
               SET positionSec = ?, deleted = ?, localUpdatedAt = ?, lastSyncedAt = ?
               WHERE sourceId = ? AND itemId = ? AND localUpdatedAt = ?""",
            7,
        ) {
            bindDouble(0, positionSec)
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
        if (getByItemId(sourceId, itemId) == null) {
            upsert(AudiobookPositionEntity(sourceId, itemId, positionSec, serverStamp, serverStamp, deleted))
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
            """UPDATE audiobook_positions SET localUpdatedAt = ?, lastSyncedAt = ?
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
            """UPDATE audiobook_positions SET lastSyncedAt = localUpdatedAt
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

    override suspend fun dirtyForSource(sourceId: String): List<AudiobookPositionEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, positionSec, localUpdatedAt, lastSyncedAt, deleted FROM audiobook_positions WHERE sourceId = ? AND localUpdatedAt > lastSyncedAt",
            ::mapRows,
            1,
        ) { bindString(0, sourceId) }.value

    override suspend fun sourcesWithDirtyRows(): List<String> =
        driver.executeQuery(
            null,
            "SELECT DISTINCT sourceId FROM audiobook_positions WHERE localUpdatedAt > lastSyncedAt",
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
            "UPDATE audiobook_positions SET deleted = 1, localUpdatedAt = ? WHERE sourceId = ? AND itemId = ?",
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
            """UPDATE audiobook_positions
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

    override suspend fun allForSource(sourceId: String): List<AudiobookPositionEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, itemId, positionSec, localUpdatedAt, lastSyncedAt, deleted FROM audiobook_positions WHERE sourceId = ?",
            ::mapRows,
            1,
        ) { bindString(0, sourceId) }.value

    private fun mapRows(cursor: SqlCursor): QueryResult<List<AudiobookPositionEntity>> {
        val result = mutableListOf<AudiobookPositionEntity>()
        while (cursor.next().value) {
            result += AudiobookPositionEntity(
                sourceId = cursor.getString(0)!!,
                itemId = cursor.getString(1)!!,
                positionSec = cursor.getDouble(2)!!,
                localUpdatedAt = cursor.getLong(3)!!,
                lastSyncedAt = cursor.getLong(4)!!,
                deleted = cursor.getLong(5) == 1L,
            )
        }
        return QueryResult.Value(result)
    }
}
