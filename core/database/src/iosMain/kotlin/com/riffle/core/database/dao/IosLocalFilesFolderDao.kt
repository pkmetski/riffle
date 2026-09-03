package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosLocalFilesFolderDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LocalFilesFolderDao {

    override suspend fun upsert(entity: LocalFilesFolderEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO local_files_folders (sourceId, treeUri, displayName, addedAtEpochMs, libraryId) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.treeUri)
            bindString(2, entity.displayName)
            bindLong(3, entity.addedAtEpochMs)
            bindString(4, entity.libraryId)
        }
        invalidator.invalidate()
    }

    override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, treeUri, displayName, addedAtEpochMs, libraryId FROM local_files_folders WHERE sourceId = ? ORDER BY addedAtEpochMs ASC",
            ::mapRows, 1,
        ) { bindString(0, sourceId) }.value

    override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> =
        invalidator.version.flatMapLatest { flow { emit(forSource(sourceId)) } }

    override suspend fun getByLibraryId(sourceId: String, libraryId: String): LocalFilesFolderEntity? =
        driver.executeQuery(
            null,
            "SELECT sourceId, treeUri, displayName, addedAtEpochMs, libraryId FROM local_files_folders WHERE sourceId = ? AND libraryId = ? LIMIT 1",
            ::mapRows, 2,
        ) { bindString(0, sourceId); bindString(1, libraryId) }.value.firstOrNull()

    override suspend fun delete(sourceId: String, treeUri: String) {
        driver.execute(
            null,
            "DELETE FROM local_files_folders WHERE sourceId = ? AND treeUri = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, treeUri) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<LocalFilesFolderEntity>> {
        val result = mutableListOf<LocalFilesFolderEntity>()
        while (cursor.next().value) {
            result += LocalFilesFolderEntity(
                sourceId = cursor.getString(0)!!,
                treeUri = cursor.getString(1)!!,
                displayName = cursor.getString(2)!!,
                addedAtEpochMs = cursor.getLong(3)!!,
                libraryId = cursor.getString(4)!!,
            )
        }
        return QueryResult.Value(result)
    }
}
