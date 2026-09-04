package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.LibraryItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosCollectionDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : CollectionDao {

    override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> =
        invalidator.version.flatMapLatest {
            flow { emit(queryByLibraryId(libraryId)) }
        }

    override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    """SELECT $LI_COLS FROM library_items li
                       INNER JOIN collection_items ci ON li.sourceId = ci.sourceId AND li.id = ci.itemId
                       WHERE ci.sourceId = ? AND ci.collectionId = ?
                       ORDER BY li.title ASC""",
                    ::mapLibraryItemRows, 2,
                ) { bindString(0, sourceId); bindString(1, collectionId) }.value)
            }
        }

    override suspend fun upsertAll(collections: List<CollectionEntity>) {
        collections.forEach { c ->
            driver.execute(
                null,
                "INSERT OR REPLACE INTO collections (id, libraryId, name, bookCount) VALUES (?, ?, ?, ?)",
                4,
            ) {
                bindString(0, c.id)
                bindString(1, c.libraryId)
                bindString(2, c.name)
                bindLong(3, c.bookCount.toLong())
            }
        }
        if (collections.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun upsertAllItems(items: List<CollectionItemEntity>) {
        items.forEach { item ->
            driver.execute(
                null,
                "INSERT OR REPLACE INTO collection_items (collectionId, sourceId, itemId) VALUES (?, ?, ?)",
                3,
            ) {
                bindString(0, item.collectionId)
                bindString(1, item.sourceId)
                bindString(2, item.itemId)
            }
        }
        if (items.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun deleteByLibraryId(libraryId: String) {
        driver.execute(null, "DELETE FROM collections WHERE libraryId = ?", 1) {
            bindString(0, libraryId)
        }
        invalidator.invalidate()
    }

    override suspend fun deleteItemsByLibraryId(libraryId: String) {
        driver.execute(
            null,
            "DELETE FROM collection_items WHERE collectionId IN (SELECT id FROM collections WHERE libraryId = ?)",
            1,
        ) { bindString(0, libraryId) }
    }

    private fun queryByLibraryId(libraryId: String): List<CollectionEntity> =
        driver.executeQuery(
            null,
            "SELECT id, libraryId, name, bookCount FROM collections WHERE libraryId = ? ORDER BY name ASC",
            { cursor ->
                val result = mutableListOf<CollectionEntity>()
                while (cursor.next().value) result.add(cursor.toCollectionEntity())
                QueryResult.Value(result)
            },
            1,
        ) { bindString(0, libraryId) }.value

    private fun SqlCursor.toCollectionEntity() = CollectionEntity(
        id = getString(0)!!,
        libraryId = getString(1)!!,
        name = getString(2)!!,
        bookCount = getLong(3)!!.toInt(),
    )

    private fun mapLibraryItemRows(cursor: SqlCursor): QueryResult.Value<List<LibraryItemEntity>> {
        val result = mutableListOf<LibraryItemEntity>()
        while (cursor.next().value) result.add(cursor.toLibraryItemEntity())
        return QueryResult.Value(result)
    }

    private fun SqlCursor.toLibraryItemEntity() = LibraryItemEntity(
        sourceId = getString(0)!!,
        id = getString(1)!!,
        libraryId = getString(2)!!,
        title = getString(3)!!,
        author = getString(4)!!,
        coverUrl = getString(5),
        readingProgress = getDouble(6)?.toFloat() ?: 0f,
        ebookFileIno = getString(7),
        ebookFormat = getString(8)!!,
        hasAudio = getLong(9)!! != 0L,
        audioDurationSec = getDouble(10)!!,
        description = getString(11),
        seriesName = getString(12),
        seriesSequence = getString(13),
        publishedYear = getString(14),
        genres = getString(15)!!,
        publisher = getString(16),
        language = getString(17),
        lastOpenedAt = getLong(18),
        addedAt = getLong(19)!!,
        isbn = getString(20),
        asin = getString(21),
        finishedAt = getLong(22),
        pageCount = getLong(23)?.toInt(),
    )

    companion object {
        private const val LI_COLS =
            "li.sourceId, li.id, li.libraryId, li.title, li.author, li.coverUrl, " +
            "li.readingProgress, li.ebookFileIno, li.ebookFormat, li.hasAudio, li.audioDurationSec, " +
            "li.description, li.seriesName, li.seriesSequence, li.publishedYear, li.genres, li.publisher, " +
            "li.language, li.lastOpenedAt, li.addedAt, li.isbn, li.asin, li.finishedAt, li.pageCount"
    }
}
