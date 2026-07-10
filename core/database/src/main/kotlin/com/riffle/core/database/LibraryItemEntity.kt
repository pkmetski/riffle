package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Keyed by (sourceId, id): item ids are only unique within a Source — two Storyteller Sources
// each emit "1", "2", … (ADR 0025). sourceId FK-cascades so removing a Source clears its items.
@Entity(
    tableName = "library_items",
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
data class LibraryItemEntity(
    val sourceId: String,
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val readingProgress: Float,
    val ebookFileIno: String? = null,
    val ebookFormat: String = "unsupported",
    val hasAudio: Boolean = false,
    val audioDurationSec: Double = 0.0,
    val description: String? = null,
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    val publishedYear: String? = null,
    val genres: String = "",
    val publisher: String? = null,
    val language: String? = null,
    val lastOpenedAt: Long? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val finishedAt: Long? = null,
)
