package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.database.IosInvalidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS = "id, sourceId, itemId, positionSec, title, createdAt, localUpdatedAt, lastSyncedAt, deleted"

@Suppress("TooManyFunctions")
internal class IosAudiobookBookmarkDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : AudiobookBookmarkDao {

    override suspend fun upsert(entity: AudiobookBookmarkEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO audiobook_bookmarks ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9,
        ) {
            bindString(0, entity.id)
            bindString(1, entity.sourceId)
            bindString(2, entity.itemId)
            bindDouble(3, entity.positionSec)
            bindString(4, entity.title)
            bindLong(5, entity.createdAt)
            bindLong(6, entity.localUpdatedAt)
            bindLong(7, entity.lastSyncedAt)
            bindLong(8, if (entity.deleted) 1L else 0L)
        }
        invalidator.invalidate()
    }

    override fun observeForItem(sourceId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT $ALL_COLS FROM audiobook_bookmarks WHERE sourceId = ? AND itemId = ? AND deleted = 0 ORDER BY positionSec ASC",
                        ::mapRows,
                        2,
                    ) { bindString(0, sourceId); bindString(1, itemId) }.value,
                )
            }
        }

    override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmarkEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT $ALL_COLS FROM audiobook_bookmarks WHERE sourceId = ? AND deleted = 0 ORDER BY positionSec ASC",
                        ::mapRows,
                        1,
                    ) { bindString(0, sourceId) }.value,
                )
            }
        }

    override suspend fun getById(id: String): AudiobookBookmarkEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM audiobook_bookmarks WHERE id = ? LIMIT 1",
            ::mapRows,
            1,
        ) { bindString(0, id) }.value.firstOrNull()

    override suspend fun allForItem(sourceId: String, itemId: String): List<AudiobookBookmarkEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM audiobook_bookmarks WHERE sourceId = ? AND itemId = ?",
            ::mapRows,
            2,
        ) { bindString(0, sourceId); bindString(1, itemId) }.value

    override suspend fun dirtyForSource(sourceId: String): List<AudiobookBookmarkEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM audiobook_bookmarks WHERE sourceId = ? AND localUpdatedAt > lastSyncedAt",
            ::mapRows,
            1,
        ) { bindString(0, sourceId) }.value

    override suspend fun sourcesWithDirtyRows(): List<String> =
        driver.executeQuery(
            null,
            "SELECT DISTINCT sourceId FROM audiobook_bookmarks WHERE localUpdatedAt > lastSyncedAt",
            { cursor ->
                val result = mutableListOf<String>()
                while (cursor.next().value) result += cursor.getString(0)!!
                QueryResult.Value(result)
            },
            0,
        ).value

    override fun observeDirtyCountForItem(sourceId: String, itemId: String): Flow<Int> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT COUNT(*) FROM audiobook_bookmarks WHERE sourceId = ? AND itemId = ? AND localUpdatedAt > lastSyncedAt",
                        { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
                        2,
                    ) { bindString(0, sourceId); bindString(1, itemId) }.value,
                )
            }
        }

    override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int {
        val count = driver.execute(
            null,
            "UPDATE audiobook_bookmarks SET lastSyncedAt = ?, localUpdatedAt = ? WHERE id = ? AND localUpdatedAt = ?",
            4,
        ) {
            bindLong(0, serverStamp)
            bindLong(1, serverStamp)
            bindString(2, id)
            bindLong(3, ifLocalUpdatedAt)
        }.value.toInt()
        if (count > 0) invalidator.invalidate()
        return count
    }

    override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Int {
        val count = driver.execute(
            null,
            "DELETE FROM audiobook_bookmarks WHERE id = ? AND deleted = 1 AND localUpdatedAt = ?",
            2,
        ) { bindString(0, id); bindLong(1, ifLocalUpdatedAt) }.value.toInt()
        if (count > 0) invalidator.invalidate()
        return count
    }

    override suspend fun hardDelete(id: String) {
        driver.execute(null, "DELETE FROM audiobook_bookmarks WHERE id = ?", 1) { bindString(0, id) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<AudiobookBookmarkEntity>> {
        val result = mutableListOf<AudiobookBookmarkEntity>()
        while (cursor.next().value) {
            result += AudiobookBookmarkEntity(
                id = cursor.getString(0)!!,
                sourceId = cursor.getString(1)!!,
                itemId = cursor.getString(2)!!,
                positionSec = cursor.getDouble(3)!!,
                title = cursor.getString(4)!!,
                createdAt = cursor.getLong(5)!!,
                localUpdatedAt = cursor.getLong(6) ?: 0L,
                lastSyncedAt = cursor.getLong(7) ?: 0L,
                deleted = cursor.getLong(8) == 1L,
            )
        }
        return QueryResult.Value(result)
    }
}
