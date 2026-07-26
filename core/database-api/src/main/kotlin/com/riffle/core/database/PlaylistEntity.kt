package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Playlists mirrored from the active Source (ADR 0027). Keyed by (sourceId, id) — playlist ids are
// only unique within an ABS instance, so two Sources against the same instance would collide.
// sourceId FK-cascades so removing a Source clears its playlists, mirroring LibraryEntity.
@Entity(
    tableName = "playlists",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId"), Index("rootId")],
)
data class PlaylistEntity(
    val id: String,
    val sourceId: String,
    val rootId: String,
    val name: String,
    val bookCount: Int,
)
