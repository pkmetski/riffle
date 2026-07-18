package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// itemIds membership per playlist, ordered via orderIndex so cold-start observation reconstructs
// the same sequence the server returned on the last refresh (ABS playlists are user-ordered).
// sourceId is duplicated on this row for the composite PK — ABS item ids collide across servers.
// FK-cascades to `playlists(sourceId, id)` so removing a playlist (or the owning source, which
// itself cascades to playlists) drops these rows too — no dangling join rows.
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "sourceId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["sourceId", "id"],
            childColumns = ["sourceId", "playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId", "playlistId")],
)
data class PlaylistItemEntity(
    val playlistId: String,
    val sourceId: String,
    val itemId: String,
    val orderIndex: Int,
)
