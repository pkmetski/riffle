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
 * Annotations are scoped to the ABS Library Item ([sourceId] + [itemId]) and exist only on the
 * ABS side; Storyteller-only books and the Readaloud reading side carry none (ADR 0024).
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId", "itemId"])],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val itemId: String,
    val type: String = TYPE_HIGHLIGHT,
    val cfi: String,
    val color: String = COLOR_YELLOW,
    val note: String? = null,
    val textSnippet: String,
    val textBefore: String = "",
    val textAfter: String = "",
    val chapterHref: String,
    val spineIndex: Int = 0,
    val progression: Double = 0.0,
    val bookmarkTitle: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val originDeviceId: String,
    val lastModifiedByDeviceId: String,
    val deleted: Boolean = false,
    /** ADR 0036: stamp of the last successful PUT for this row. `updatedAt > lastSyncedAt` ⇒ dirty. */
    val lastSyncedAt: Long = 0L,
    /** JSON list of figures enclosed by a TYPE_HIGHLIGHT annotation's range. Null on TYPE_IMAGE and TYPE_BOOKMARK. */
    val embeddedFigures: String? = null,
    /** Href of the source image for a TYPE_IMAGE annotation. */
    val imageHref: String? = null,
    /** Inline SVG markup for a TYPE_IMAGE annotation, when the figure is an SVG. */
    val imageSvg: String? = null,
) {
    companion object {
        const val TYPE_HIGHLIGHT = "HIGHLIGHT"
        const val TYPE_BOOKMARK = "BOOKMARK"
        const val TYPE_IMAGE = "IMAGE"
        const val COLOR_YELLOW = "yellow"
    }
}
