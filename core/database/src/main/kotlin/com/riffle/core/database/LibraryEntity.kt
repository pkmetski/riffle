package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Keyed by (serverId, id): library ids are only unique within an Audiobookshelf instance, so two
// Servers pointing at the same instance emit identical ids (issue #113). serverId FK-cascades so
// removing a Server clears its libraries — mirroring [LibraryItemEntity] (ADR 0025).
@Entity(
    tableName = "libraries",
    primaryKeys = ["serverId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class LibraryEntity(
    val id: String,
    val name: String,
    val mediaType: String,
    val serverId: String,
    val isUnsupported: Boolean = false,
)
