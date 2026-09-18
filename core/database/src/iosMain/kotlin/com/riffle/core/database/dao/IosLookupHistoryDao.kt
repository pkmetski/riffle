package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LookupHistoryDao
import com.riffle.core.database.LookupHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosLookupHistoryDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : LookupHistoryDao {

    override fun observeRecent(languageTag: String, limit: Int): Flow<List<String>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT form FROM lookup_history WHERE languageTag = ? ORDER BY lookedUpAt DESC LIMIT ?",
                        { cursor ->
                            val result = mutableListOf<String>()
                            while (cursor.next().value) result += cursor.getString(0)!!
                            QueryResult.Value(result)
                        },
                        2,
                    ) { bindString(0, languageTag); bindLong(1, limit.toLong()) }.value,
                )
            }
        }

    override suspend fun insert(entity: LookupHistoryEntity) {
        driver.execute(
            null,
            "INSERT INTO lookup_history (languageTag, form, lookedUpAt) VALUES (?, ?, ?)",
            3,
        ) {
            bindString(0, entity.languageTag)
            bindString(1, entity.form)
            bindLong(2, entity.lookedUpAt)
        }
        invalidator.invalidate()
    }

    override suspend fun pruneOldest(languageTag: String) {
        driver.execute(
            null,
            """DELETE FROM lookup_history
               WHERE languageTag = ? AND id NOT IN (
                 SELECT id FROM lookup_history WHERE languageTag = ? ORDER BY lookedUpAt DESC LIMIT 50
               )""",
            2,
        ) { bindString(0, languageTag); bindString(1, languageTag) }
        invalidator.invalidate()
    }
}
