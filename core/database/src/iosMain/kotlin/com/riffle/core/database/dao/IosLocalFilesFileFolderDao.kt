package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity

internal class IosLocalFilesFileFolderDao(
    private val driver: SqlDriver,
    @Suppress("UnusedPrivateProperty")
    private val invalidator: IosInvalidator,
) : LocalFilesFileFolderDao {

    override suspend fun upsert(entity: LocalFilesFileFolderEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO local_files_file_folders (sourceId, sourceItemId, folderTreeUri, lastSeenAtEpochMs) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, entity.sourceId)
            bindString(1, entity.sourceItemId)
            bindString(2, entity.folderTreeUri)
            bindLong(3, entity.lastSeenAtEpochMs)
        }
    }

    override suspend fun forFile(sourceId: String, sourceItemId: String): List<LocalFilesFileFolderEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, sourceItemId, folderTreeUri, lastSeenAtEpochMs FROM local_files_file_folders WHERE sourceId = ? AND sourceItemId = ?",
            ::mapRows, 2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }.value

    override suspend fun forFolder(sourceId: String, folderTreeUri: String): List<LocalFilesFileFolderEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, sourceItemId, folderTreeUri, lastSeenAtEpochMs FROM local_files_file_folders WHERE sourceId = ? AND folderTreeUri = ?",
            ::mapRows, 2,
        ) { bindString(0, sourceId); bindString(1, folderTreeUri) }.value

    override suspend fun itemIdsInFolder(sourceId: String, folderTreeUri: String): List<String> =
        driver.executeQuery(
            null,
            "SELECT sourceItemId FROM local_files_file_folders WHERE sourceId = ? AND folderTreeUri = ?",
            { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) ids += cursor.getString(0)!!
                QueryResult.Value(ids)
            }, 2,
        ) { bindString(0, sourceId); bindString(1, folderTreeUri) }.value

    override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity> =
        driver.executeQuery(
            null,
            "SELECT sourceId, sourceItemId, folderTreeUri, lastSeenAtEpochMs FROM local_files_file_folders WHERE sourceId = ? AND lastSeenAtEpochMs < ?",
            ::mapRows, 2,
        ) { bindString(0, sourceId); bindLong(1, scanStart) }.value

    override suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String) {
        driver.execute(
            null,
            "DELETE FROM local_files_file_folders WHERE sourceId = ? AND sourceItemId = ? AND folderTreeUri = ?",
            3,
        ) { bindString(0, sourceId); bindString(1, sourceItemId); bindString(2, folderTreeUri) }
    }

    override suspend fun deleteFolder(sourceId: String, folderTreeUri: String) {
        driver.execute(
            null,
            "DELETE FROM local_files_file_folders WHERE sourceId = ? AND folderTreeUri = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, folderTreeUri) }
    }

    override suspend fun deleteFile(sourceId: String, sourceItemId: String) {
        driver.execute(
            null,
            "DELETE FROM local_files_file_folders WHERE sourceId = ? AND sourceItemId = ?",
            2,
        ) { bindString(0, sourceId); bindString(1, sourceItemId) }
    }

    override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> =
        driver.executeQuery(
            null,
            """SELECT f.sourceId, f.sourceItemId, f.originalUri, f.copiedPath, f.coverPath, f.format,
               f.sizeBytes, f.mtimeEpochMs, f.lastSeenAtEpochMs, f.displayName
               FROM local_files_files f
               WHERE f.sourceId = ?
               AND NOT EXISTS (
                   SELECT 1 FROM local_files_file_folders m
                   WHERE m.sourceId = f.sourceId AND m.sourceItemId = f.sourceItemId
               )""",
            { cursor ->
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
                QueryResult.Value(result)
            }, 1,
        ) { bindString(0, sourceId) }.value

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<LocalFilesFileFolderEntity>> {
        val result = mutableListOf<LocalFilesFileFolderEntity>()
        while (cursor.next().value) {
            result += LocalFilesFileFolderEntity(
                sourceId = cursor.getString(0)!!,
                sourceItemId = cursor.getString(1)!!,
                folderTreeUri = cursor.getString(2)!!,
                lastSeenAtEpochMs = cursor.getLong(3)!!,
            )
        }
        return QueryResult.Value(result)
    }
}
