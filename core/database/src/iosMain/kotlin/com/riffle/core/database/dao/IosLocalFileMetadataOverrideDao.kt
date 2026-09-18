package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS = "sourceId, sourceItemId, title, author, seriesName, seriesIndex, coverUrl"

internal class IosLocalFileMetadataOverrideDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LocalFileMetadataOverrideDao {

    override suspend fun upsert(entity: LocalFileMetadataOverrideEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO local_file_metadata_overrides ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?)",
            7,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.sourceItemId)
            bindString(2, entity.title)
            bindString(3, entity.author)
            bindString(4, entity.seriesName)
            bindDouble(5, entity.seriesIndex)
            bindString(6, entity.coverUrl)
        }
        invalidator.invalidate()
    }

    override fun observe(sourceId: String, sourceItemId: String): Flow<LocalFileMetadataOverrideEntity?> =
        invalidator.version.flatMapLatest { flow { emit(getForItem(sourceId, sourceItemId)) } }

    override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFileMetadataOverrideEntity> {
        if (sourceItemIds.isEmpty()) return emptyList()
        val placeholders = sourceItemIds.joinToString(",") { "?" }
        return driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM local_file_metadata_overrides WHERE sourceId = ? AND sourceItemId IN ($placeholders)",
            ::mapRows,
            sourceItemIds.size + 1,
        ) {
            bindString(0, sourceId)
            sourceItemIds.forEachIndexed { i, id -> bindString(i + 1, id) }
        }.value
    }

    override suspend fun getForItem(sourceId: String, sourceItemId: String): LocalFileMetadataOverrideEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM local_file_metadata_overrides WHERE sourceId = ? AND sourceItemId = ? LIMIT 1",
            ::mapRows,
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }.value.firstOrNull()

    override suspend fun delete(sourceId: String, sourceItemId: String) {
        driver.execute(
            null,
            "DELETE FROM local_file_metadata_overrides WHERE sourceId = ? AND sourceItemId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<LocalFileMetadataOverrideEntity>> {
        val result = mutableListOf<LocalFileMetadataOverrideEntity>()
        while (cursor.next().value) {
            result += LocalFileMetadataOverrideEntity(
                sourceId = cursor.getString(0)!!,
                sourceItemId = cursor.getString(1)!!,
                title = cursor.getString(2),
                author = cursor.getString(3),
                seriesName = cursor.getString(4),
                seriesIndex = cursor.getDouble(5),
                coverUrl = cursor.getString(6),
            )
        }
        return QueryResult.Value(result)
    }
}
