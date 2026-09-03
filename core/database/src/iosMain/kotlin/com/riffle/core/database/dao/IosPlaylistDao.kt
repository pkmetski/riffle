package com.riffle.core.database.dao

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.riffle.core.database.IosInvalidator
import com.riffle.core.database.PlaylistDao
import com.riffle.core.database.PlaylistEntity
import com.riffle.core.database.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

internal class IosPlaylistDao(
    private val driver: SqlDriver,
    private val invalidator: IosInvalidator,
) : PlaylistDao {
    override fun observeByRootId(rootId: String): Flow<List<PlaylistEntity>> =
        invalidator.version.flatMapLatest {
            flow {
                emit(driver.executeQuery(
                    null,
                    "SELECT id, sourceId, rootId, name, bookCount FROM playlists WHERE rootId = ? ORDER BY name ASC",
                    ::mapPlaylists, 1,
                ) { bindString(0, rootId) }.value)
            }
        }

    override fun observeItemIds(sourceId: String, playlistId: String): Flow<List<String>> =
        invalidator.version.flatMapLatest {
            flow { emit(queryItemIds(sourceId, playlistId)) }
        }

    override suspend fun itemIds(sourceId: String, playlistId: String): List<String> =
        queryItemIds(sourceId, playlistId)

    override suspend fun getById(sourceId: String, playlistId: String): PlaylistEntity? =
        driver.executeQuery(
            null,
            "SELECT id, sourceId, rootId, name, bookCount FROM playlists WHERE sourceId = ? AND id = ? LIMIT 1",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.toPlaylistEntity() else null) },
            2,
        ) { bindString(0, sourceId); bindString(1, playlistId) }.value

    override suspend fun upsertAll(playlists: List<PlaylistEntity>) {
        playlists.forEach { pl ->
            driver.execute(null,
                "INSERT OR REPLACE INTO playlists (id, sourceId, rootId, name, bookCount) VALUES (?, ?, ?, ?, ?)",
                5) {
                bindString(0, pl.id); bindString(1, pl.sourceId)
                bindString(2, pl.rootId); bindString(3, pl.name); bindLong(4, pl.bookCount.toLong())
            }
        }
        if (playlists.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun upsertAllItems(items: List<PlaylistItemEntity>) {
        items.forEach { item ->
            driver.execute(null,
                "INSERT OR REPLACE INTO playlist_items (playlistId, sourceId, itemId, orderIndex) VALUES (?, ?, ?, ?)",
                4) {
                bindString(0, item.playlistId); bindString(1, item.sourceId)
                bindString(2, item.itemId); bindLong(3, item.orderIndex.toLong())
            }
        }
        if (items.isNotEmpty()) invalidator.invalidate()
    }

    override suspend fun deleteByRootId(rootId: String) {
        driver.execute(null, "DELETE FROM playlists WHERE rootId = ?", 1) { bindString(0, rootId) }
        invalidator.invalidate()
    }

    override suspend fun deleteItemsByRootId(rootId: String) {
        driver.execute(null,
            "DELETE FROM playlist_items WHERE playlistId IN (SELECT id FROM playlists WHERE rootId = ?)",
            1) { bindString(0, rootId) }
    }

    override suspend fun deletePlaylist(sourceId: String, playlistId: String) {
        driver.execute(null, "DELETE FROM playlists WHERE sourceId = ? AND id = ?", 2) {
            bindString(0, sourceId); bindString(1, playlistId)
        }
        invalidator.invalidate()
    }

    override suspend fun deletePlaylistItems(sourceId: String, playlistId: String) {
        driver.execute(null,
            "DELETE FROM playlist_items WHERE sourceId = ? AND playlistId = ?", 2) {
            bindString(0, sourceId); bindString(1, playlistId)
        }
    }

    private fun queryItemIds(sourceId: String, playlistId: String): List<String> =
        driver.executeQuery(
            null,
            "SELECT itemId FROM playlist_items WHERE sourceId = ? AND playlistId = ? ORDER BY orderIndex ASC",
            { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) ids.add(cursor.getString(0)!!)
                QueryResult.Value(ids)
            },
            2,
        ) { bindString(0, sourceId); bindString(1, playlistId) }.value

    private fun mapPlaylists(cursor: SqlCursor): QueryResult.Value<List<PlaylistEntity>> {
        val result = mutableListOf<PlaylistEntity>()
        while (cursor.next().value) result.add(cursor.toPlaylistEntity())
        return QueryResult.Value(result)
    }

    private fun SqlCursor.toPlaylistEntity() = PlaylistEntity(
        id = getString(0)!!,
        sourceId = getString(1)!!,
        rootId = getString(2)!!,
        name = getString(3)!!,
        bookCount = getLong(4)!!.toInt(),
    )
}
