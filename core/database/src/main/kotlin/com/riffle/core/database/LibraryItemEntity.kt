package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Keyed by (serverId, id): item ids are only unique within a Server — two Storyteller Servers
// each emit "1", "2", … (ADR 0025). serverId FK-cascades so removing a Server clears its items.
@Entity(
    tableName = "library_items",
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
data class LibraryItemEntity(
    val serverId: String,
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val readingProgress: Float,
    val ebookFileIno: String? = null,
    val ebookFormat: String = "unsupported",
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: String = "",
    val publisher: String? = null,
    val lastOpenedAt: Long? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
)
