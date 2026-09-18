package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS = "storytellerSourceId, storytellerBookId, scope, absSourceId, absLibraryItemId"

internal class IosReadaloudDismissalDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : ReadaloudDismissalDao {

    override suspend fun upsert(entity: ReadaloudDismissalEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO readaloud_dismissals ($ALL_COLS) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, entity.storytellerSourceId)
            bindString(1, entity.storytellerBookId)
            bindString(2, entity.scope)
            bindString(3, entity.absSourceId)
            bindString(4, entity.absLibraryItemId)
        }
        invalidator.invalidate()
    }

    override suspend fun allRows(): List<ReadaloudDismissalEntity> =
        driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_dismissals", ::mapRows, 0).value

    override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_dismissals", ::mapRows, 0).value) }
        }

    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudDismissalEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM readaloud_dismissals WHERE storytellerSourceId = ? AND storytellerBookId = ?",
            ::mapRows,
            2,
        ) { bindString(0, storytellerSourceId); bindString(1, storytellerBookId) }.value

    override suspend fun isBookDismissed(storytellerSourceId: String, storytellerBookId: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) > 0 FROM readaloud_dismissals WHERE storytellerSourceId = ? AND storytellerBookId = ? AND scope = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getLong(0) == 1L else false) },
            3,
        ) {
            bindString(0, storytellerSourceId)
            bindString(1, storytellerBookId)
            bindString(2, ReadaloudDismissalEntity.SCOPE_BOOK)
        }.value

    override suspend fun clearBookDismissal(storytellerSourceId: String, storytellerBookId: String) {
        driver.execute(
            null,
            "DELETE FROM readaloud_dismissals WHERE storytellerSourceId = ? AND storytellerBookId = ? AND scope = ?",
            3,
        ) {
            bindString(0, storytellerSourceId)
            bindString(1, storytellerBookId)
            bindString(2, ReadaloudDismissalEntity.SCOPE_BOOK)
        }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<ReadaloudDismissalEntity>> {
        val result = mutableListOf<ReadaloudDismissalEntity>()
        while (cursor.next().value) {
            result += ReadaloudDismissalEntity(
                storytellerSourceId = cursor.getString(0)!!,
                storytellerBookId = cursor.getString(1)!!,
                scope = cursor.getString(2)!!,
                absSourceId = cursor.getString(3) ?: "",
                absLibraryItemId = cursor.getString(4) ?: "",
            )
        }
        return QueryResult.Value(result)
    }
}
