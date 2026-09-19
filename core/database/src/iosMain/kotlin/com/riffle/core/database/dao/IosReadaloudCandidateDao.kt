package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS = "storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId, score"

internal class IosReadaloudCandidateDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : ReadaloudCandidateDao {

    override suspend fun upsert(entity: ReadaloudCandidateEntity) {
        executeUpsert(entity)
        invalidator.invalidate()
    }

    override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) {
        entities.forEach { executeUpsert(it) }
        if (entities.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun allRows(): List<ReadaloudCandidateEntity> =
        driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_candidates", ::mapRows, 0).value

    override suspend fun clearAll() {
        driver.execute(null, "DELETE FROM readaloud_candidates", 0)
        invalidator.invalidate()
    }

    override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(driver.executeQuery(null, "SELECT $ALL_COLS FROM readaloud_candidates", ::mapRows, 0).value) }
        }

    override fun observeForStorytellerSource(storytellerSourceId: String): Flow<List<ReadaloudCandidateEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT $ALL_COLS FROM readaloud_candidates WHERE storytellerSourceId = ?",
                        ::mapRows,
                        1,
                    ) { bindString(0, storytellerSourceId) }.value,
                )
            }
        }

    override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) {
        driver.execute(
            null,
            "DELETE FROM readaloud_candidates WHERE storytellerSourceId = ? AND storytellerBookId = ?",
            2,
        ) { bindString(0, storytellerSourceId); bindString(1, storytellerBookId) }
        invalidator.invalidate()
    }

    override suspend fun deleteCandidate(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    ) {
        driver.execute(
            null,
            "DELETE FROM readaloud_candidates WHERE storytellerSourceId = ? AND storytellerBookId = ? " +
                "AND absSourceId = ? AND absLibraryItemId = ?",
            4,
        ) {
            bindString(0, storytellerSourceId)
            bindString(1, storytellerBookId)
            bindString(2, absSourceId)
            bindString(3, absLibraryItemId)
        }
        invalidator.invalidate()
    }

    private fun executeUpsert(entity: ReadaloudCandidateEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO readaloud_candidates ($ALL_COLS) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, entity.storytellerSourceId)
            bindString(1, entity.storytellerBookId)
            bindString(2, entity.absSourceId)
            bindString(3, entity.absLibraryItemId)
            bindDouble(4, entity.score)
        }
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<ReadaloudCandidateEntity>> {
        val result = mutableListOf<ReadaloudCandidateEntity>()
        while (cursor.next().value) {
            result += ReadaloudCandidateEntity(
                storytellerSourceId = cursor.getString(0)!!,
                storytellerBookId = cursor.getString(1)!!,
                absSourceId = cursor.getString(2)!!,
                absLibraryItemId = cursor.getString(3)!!,
                score = cursor.getDouble(4)!!,
            )
        }
        return QueryResult.Value(result)
    }
}
