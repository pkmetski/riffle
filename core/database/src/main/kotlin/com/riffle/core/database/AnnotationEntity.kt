package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reader Annotation (Highlight in v1; Notes/Bookmarks land in later slices).
 *
 * The primary, always-queryable store (ADR 0025). Every field the future W3C Web Annotation
 * format + per-device-file merge needs is carried now even though v1 has no sync:
 *
 * - [id] — stable client-generated UUID; the annotation's identity across devices.
 * - [originDeviceId] / [lastModifiedByDeviceId] — provenance for last-write-wins merge.
 * - [deleted] — tombstone so deletes propagate instead of vanishing.
 * - [cfi] — the load-bearing anchor in the ABS-EPUB coordinate system; a CFI *range* for
 *   highlights/notes, a CFI *point* for bookmarks (ADR 0024).
 * - [textSnippet] + [chapterHref] — human-readable fallback / re-anchoring aid.
 *
 * Annotations are scoped to the ABS Library Item ([serverId] + [itemId]) and exist only on the
 * ABS side; Storyteller-only books and the Readaloud reading side carry none (ADR 0024).
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["serverId", "itemId"])],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val itemId: String,
    val type: String = TYPE_HIGHLIGHT,
    val cfi: String,
    val color: String = COLOR_YELLOW,
    val note: String? = null,
    val textSnippet: String,
    val chapterHref: String,
    val createdAt: Long,
    val updatedAt: Long,
    val originDeviceId: String,
    val lastModifiedByDeviceId: String,
    val deleted: Boolean = false,
) {
    companion object {
        const val TYPE_HIGHLIGHT = "HIGHLIGHT"
        const val TYPE_BOOKMARK = "BOOKMARK"
        const val COLOR_YELLOW = "yellow"
    }
}
