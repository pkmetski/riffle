package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.IosInvalidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

private const val ALL_COLS = "languageTag, packVersion, installedAt, sizeBytes, attributionHtml, licenseUrl, state"

internal class IosDictionaryPackDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : DictionaryPackDao {

    override fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?> =
        invalidator.version.flatMapLatest {
            flow {
                emit(
                    driver.executeQuery(
                        null,
                        "SELECT $ALL_COLS FROM dictionary_packs WHERE languageTag = ? LIMIT 1",
                        ::mapRows,
                        1,
                    ) { bindString(0, languageTag) }.value.firstOrNull(),
                )
            }
        }

    override fun observeAll(): Flow<List<DictionaryPackEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(driver.executeQuery(null, "SELECT $ALL_COLS FROM dictionary_packs", ::mapRows, 0).value) }
        }

    override suspend fun upsert(entity: DictionaryPackEntity) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO dictionary_packs ($ALL_COLS) VALUES (?, ?, ?, ?, ?, ?, ?)",
            7,
        ) {
            bindString(0, entity.languageTag)
            bindString(1, entity.packVersion)
            bindLong(2, entity.installedAt)
            bindLong(3, entity.sizeBytes)
            bindString(4, entity.attributionHtml)
            bindString(5, entity.licenseUrl)
            bindString(6, entity.state)
        }
        invalidator.invalidate()
    }

    override suspend fun updateState(languageTag: String, state: String) {
        driver.execute(
            null,
            "UPDATE dictionary_packs SET state = ? WHERE languageTag = ?",
            2,
        ) { bindString(0, state); bindString(1, languageTag) }
        invalidator.invalidate()
    }

    override suspend fun delete(languageTag: String) {
        driver.execute(null, "DELETE FROM dictionary_packs WHERE languageTag = ?", 1) { bindString(0, languageTag) }
        invalidator.invalidate()
    }

    private fun mapRows(cursor: SqlCursor): QueryResult.Value<List<DictionaryPackEntity>> {
        val result = mutableListOf<DictionaryPackEntity>()
        while (cursor.next().value) {
            result += DictionaryPackEntity(
                languageTag = cursor.getString(0)!!,
                packVersion = cursor.getString(1)!!,
                installedAt = cursor.getLong(2)!!,
                sizeBytes = cursor.getLong(3)!!,
                attributionHtml = cursor.getString(4)!!,
                licenseUrl = cursor.getString(5)!!,
                state = cursor.getString(6)!!,
            )
        }
        return QueryResult.Value(result)
    }
}
