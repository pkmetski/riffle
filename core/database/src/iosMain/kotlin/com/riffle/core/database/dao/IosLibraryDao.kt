package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosLibraryDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LibraryDao {

    override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(queryLibrariesForSource(sourceId)) }
        }

    override suspend fun libraryIdsForSource(sourceId: String): List<String> =
        driver.executeQuery(
            null,
            "SELECT id FROM libraries WHERE sourceId = ?",
            { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) ids.add(cursor.getString(0)!!)
                QueryResult.Value(ids)
            },
            1,
        ) { bindString(0, sourceId) }.value

    override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
        driver.executeQuery(
            null,
            "SELECT id, name, mediaType, sourceId, isUnsupported FROM libraries WHERE sourceId = ? AND id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toLibraryEntity() else null) },
            2,
        ) {
            bindString(0, sourceId)
            bindString(1, libraryId)
        }.value

    override suspend fun upsertAll(libraries: List<LibraryEntity>) {
        libraries.forEach { lib ->
            driver.execute(
                null,
                "INSERT OR REPLACE INTO libraries (id, name, mediaType, sourceId, isUnsupported) VALUES (?, ?, ?, ?, ?)",
                5,
            ) {
                bindString(0, lib.id)
                bindString(1, lib.name)
                bindString(2, lib.mediaType)
                bindString(3, lib.sourceId)
                bindLong(4, if (lib.isUnsupported) 1L else 0L)
            }
        }
        if (libraries.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun deleteBySourceId(sourceId: String) {
        driver.execute(null, "DELETE FROM libraries WHERE sourceId = ?", 1) {
            bindString(0, sourceId)
        }
        invalidator.invalidate()
    }

    override suspend fun deleteById(sourceId: String, libraryId: String) {
        driver.execute(null, "DELETE FROM libraries WHERE sourceId = ? AND id = ?", 2) {
            bindString(0, sourceId)
            bindString(1, libraryId)
        }
        invalidator.invalidate()
    }

    override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
        driver.execute(null,
            "UPDATE libraries SET isUnsupported = ? WHERE sourceId = ? AND id = ?", 3) {
            bindLong(0, if (isUnsupported) 1L else 0L)
            bindString(1, sourceId)
            bindString(2, libraryId)
        }
        invalidator.invalidate()
    }

    private fun queryLibrariesForSource(sourceId: String): List<LibraryEntity> =
        driver.executeQuery(
            null,
            "SELECT id, name, mediaType, sourceId, isUnsupported FROM libraries WHERE sourceId = ? ORDER BY name ASC",
            { cursor ->
                val result = mutableListOf<LibraryEntity>()
                while (cursor.next().value) result.add(cursor.toLibraryEntity())
                QueryResult.Value(result)
            },
            1,
        ) { bindString(0, sourceId) }.value

    private fun SqlCursor.toLibraryEntity() = LibraryEntity(
        id = getString(0)!!,
        name = getString(1)!!,
        mediaType = getString(2)!!,
        sourceId = getString(3)!!,
        isUnsupported = getLong(4)!! != 0L,
    )
}
