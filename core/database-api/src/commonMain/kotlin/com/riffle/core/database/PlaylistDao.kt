package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists WHERE rootId = :rootId ORDER BY name ASC")
    fun observeByRootId(rootId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT itemId FROM playlist_items WHERE sourceId = :sourceId AND playlistId = :playlistId ORDER BY orderIndex ASC")
    fun observeItemIds(sourceId: String, playlistId: String): Flow<List<String>>

    @Query("SELECT itemId FROM playlist_items WHERE sourceId = :sourceId AND playlistId = :playlistId ORDER BY orderIndex ASC")
    suspend fun itemIds(sourceId: String, playlistId: String): List<String>

    @Query("SELECT * FROM playlists WHERE sourceId = :sourceId AND id = :playlistId")
    suspend fun getById(sourceId: String, playlistId: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlists WHERE rootId = :rootId")
    suspend fun deleteByRootId(rootId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId IN (SELECT id FROM playlists WHERE rootId = :rootId)")
    suspend fun deleteItemsByRootId(rootId: String)

    @Query("DELETE FROM playlists WHERE sourceId = :sourceId AND id = :playlistId")
    suspend fun deletePlaylist(sourceId: String, playlistId: String)

    @Query("DELETE FROM playlist_items WHERE sourceId = :sourceId AND playlistId = :playlistId")
    suspend fun deletePlaylistItems(sourceId: String, playlistId: String)

    @Transaction
    suspend fun replaceAllForRoot(
        rootId: String,
        playlists: List<PlaylistEntity>,
        items: List<PlaylistItemEntity>,
    ) {
        deleteItemsByRootId(rootId)
        deleteByRootId(rootId)
        upsertAll(playlists)
        upsertAllItems(items)
    }

    @Transaction
    suspend fun replacePlaylist(
        playlist: PlaylistEntity,
        items: List<PlaylistItemEntity>,
    ) {
        deletePlaylistItems(playlist.sourceId, playlist.id)
        upsertAll(listOf(playlist))
        upsertAllItems(items)
    }
}
