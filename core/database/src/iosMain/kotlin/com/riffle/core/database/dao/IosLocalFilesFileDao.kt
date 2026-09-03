package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity

private const val ALL_COLS =
    "sourceId, sourceItemId, originalUri, copiedPath, coverPath, format, sizeBytes, mtimeEpochMs, lastSeenAtEpochMs, displayName"

internal class IosLocalFilesFileDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LocalFilesFileDao {

    override suspend fun upsert(entity: LocalFilesFileEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO local_files_files ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            10,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.sourceItemId)
            bindString(2, entity.originalUri)
            bindString(3, entity.copiedPath)
            bindString(4, entity.coverPath)
            bindString(5, entity.format)
            bindLong(6, entity.sizeBytes)
            bindLong(7, entity.mtimeEpochMs)
            bindLong(8, entity.lastSeenAtEpochMs)
            bindString(9, entity.displayName)
        }
        invalidator.invalidate()
    }

    override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM local_files_files WHERE sourceId = ? AND sourceItemId = ? LIMIT 1",
            ::mapRows, 2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }.value.firstOrNull()

    override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM local_files_files WHERE sourceId = ?",
            ::mapRows, 1,
        ) { bindString(0, sourceId) }.value

    override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFilesFileEntity> {
        if (sourceItemIds.isEmpty()) return emptyList()
        val placeholders = sourceItemIds.joinToString(",") { "?" }
        return driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM local_files_files WHERE sourceId = ? AND sourceItemId IN ($placeholders)",
            ::mapRows,
            sourceItemIds.size + 1,
        ) {
            bindString(0, sourceId)
            sourceItemIds.forEachIndexed { i, id -> bindString(i + 1, id) }
        }.value
    }

    override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {
        driver.execute(
            null,
            "UPDATE local_files_files SET lastSeenAtEpochMs = ? WHERE sourceId = ? AND sourceItemId = ?",
            3,
        ) { bindLong(0, seenAt); bindString(1, sourceId); bindString(2, sourceItemId) }
    }

    override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {
        driver.execute(
            null,
            "UPDATE local_files_files SET displayName = ? WHERE sourceId = ? AND sourceItemId = ?",
            3,
        ) { bindString(0, displayName); bindString(1, sourceId); bindString(2, sourceItemId) }
    }

    override suspend fun delete(sourceId: String, sourceItemId: String) {
        driver.execute(
            null,
            "DELETE FROM local_files_files WHERE sourceId = ? AND sourceItemId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<LocalFilesFileEntity>> {
        val result = mutableListOf<LocalFilesFileEntity>()
        while (cursor.next().value) {
            result += LocalFilesFileEntity(
                sourceId = cursor.getString(0)!!,
                sourceItemId = cursor.getString(1)!!,
                originalUri = cursor.getString(2)!!,
                copiedPath = cursor.getString(3)!!,
                coverPath = cursor.getString(4),
                format = cursor.getString(5)!!,
                sizeBytes = cursor.getLong(6)!!,
                mtimeEpochMs = cursor.getLong(7)!!,
                lastSeenAtEpochMs = cursor.getLong(8)!!,
                displayName = cursor.getString(9) ?: "",
            )
        }
        return QueryResult.Value(result)
    }
}
