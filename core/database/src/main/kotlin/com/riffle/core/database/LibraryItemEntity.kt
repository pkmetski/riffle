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
    // Non-null so every Source is forced to stamp a real timestamp on insert. Sorting Recently
    // Added by `addedAt DESC` used to silently push null-stamped rows to the tail (or off the
    // top-50 list entirely) — most recently visible on Chitanka/Gramofonche and Storyteller
    // Readaloud items, whose network payloads carry no addedAt. Callers with no server-side
    // timestamp should stamp `clock.nowMs()` at the moment the row enters `library_items`.
    val addedAt: Long,
    val isbn: String? = null,
    val asin: String? = null,
    val finishedAt: Long? = null,
    // Non-null for formats where a discrete page count is meaningful (comics today; PDF/EPUB may
    // populate it in future). Displayed on the Detail Screen and used as the Progress-Sync
    // denominator for comics.
    val pageCount: Int? = null,
)
