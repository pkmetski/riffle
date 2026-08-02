package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Keyed by (sourceId, id): library ids are only unique within an Audiobookshelf instance, so two
// Sources pointing at the same instance emit identical ids (issue #113). sourceId FK-cascades so
// removing a Source clears its libraries — mirroring [LibraryItemEntity] (ADR 0025).
@Entity(
    tableName = "libraries",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class LibraryEntity(
    val id: String,
    val name: String,
    val mediaType: String,
    val sourceId: String,
    val isUnsupported: Boolean = false,
)
