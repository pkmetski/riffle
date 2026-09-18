package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS =
    "absSourceId, absLibraryItemId, storytellerSourceId, storytellerBookId, state, userConfirmed, createdAt, updatedAt, identityResult"

@Suppress("TooManyFunctions")
internal class IosReadaloudLinkDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : ReadaloudLinkDao {

    override suspend fun upsert(entity: ReadaloudLinkEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO readaloud_links ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9,
        ) {
            bindString(0, entity.absSourceId)
            bindString(1, entity.absLibraryItemId)
            bindString(2, entity.storytellerSourceId)
            bindString(3, entity.storytellerBookId)
            bindString(4, entity.state)
            bindLong(5, if (entity.userConfirmed) 1L else 0L)
            bindLong(6, entity.createdAt)
            bindLong(7, entity.updatedAt)
            bindString(8, entity.identityResult)
        }
        invalidator.invalidate()
    }

    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLinkEntity? =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM readaloud_links WHERE absSourceId = ? AND absLibraryItemId = ? LIMIT 1",
            ::mapRows,
            2,
        ) { bindString(0, absSourceId); bindString(1, absLibraryItemId) }.value.firstOrNull()

    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLinkEntity> =
        driver.executeQuery(
            null,
            "SELECT $ALL_COLS FROM readaloud_links WHERE storytellerSourceId = ? AND storytellerBookId = ?",
            ::mapRows,
            2,
        ) { bindString(0, storytellerSourceId); bindString(1, storytellerBookId) }.value

    override fun observeAll(): Flow<List<ReadaloudLinkEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_links", ::mapRows, 0).value) }
        }

    override suspend fun allRows(): List<ReadaloudLinkEntity> =
        driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_links", ::mapRows, 0).value

    override fun observeLinkedAbsItemIds(): Flow<List<String>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT absLibraryItemId FROM readaloud_links",
                        { cursor ->
                            val result = mutableListOf<String>()
                            while (cursor.next().value) result += cursor.getString(0)!!
                            QueryResult.Value(result)
                        },
                        0,
                    ).value,
                )
            }
        }

    override suspend fun countForSource(sourceId: String): Int =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM readaloud_links WHERE storytellerSourceId = ? OR absSourceId = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
            2,
        ) { bindString(0, sourceId); bindString(1, sourceId) }.value

    override suspend fun deleteByAbsItem(absSourceId: String, absLibraryItemId: String) {
        driver.execute(
            null,
            "DELETE FROM readaloud_links WHERE absSourceId = ? AND absLibraryItemId = ?",
            2,
        ) { bindString(0, absSourceId); bindString(1, absLibraryItemId) }
        invalidator.invalidate()
    }

    override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) {
        driver.execute(
            null,
            "DELETE FROM readaloud_links WHERE storytellerSourceId = ? AND storytellerBookId = ?",
            2,
        ) { bindString(0, storytellerSourceId); bindString(1, storytellerBookId) }
        invalidator.invalidate()
    }

    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: String) {
        driver.execute(
            null,
            "UPDATE readaloud_links SET identityResult = ? WHERE absSourceId = ? AND absLibraryItemId = ?",
            3,
        ) { bindString(0, result); bindString(1, absSourceId); bindString(2, absLibraryItemId) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<ReadaloudLinkEntity>> {
        val result = mutableListOf<ReadaloudLinkEntity>()
        while (cursor.next().value) {
            result += ReadaloudLinkEntity(
                absSourceId = cursor.getString(0)!!,
                absLibraryItemId = cursor.getString(1)!!,
                storytellerSourceId = cursor.getString(2)!!,
                storytellerBookId = cursor.getString(3)!!,
                state = cursor.getString(4) ?: ReadaloudLinkEntity.STATE_CONFIRMED,
                userConfirmed = cursor.getLong(5) == 1L,
                createdAt = cursor.getLong(6)!!,
                updatedAt = cursor.getLong(7)!!,
                identityResult = cursor.getString(8) ?: ReadaloudLinkEntity.IDENTITY_UNKNOWN,
            )
        }
        return QueryResult.Value(result)
    }
}
