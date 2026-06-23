package com.riffle.core.domain

/**
 * Parsed W3C Web Annotation (intermediate representation for sync/merge).
 *
 * This is the parsed form of a W3C annotation JSON, carrying all fields needed for
 * last-write-wins merge before conversion to AnnotationEntity.
 */
data class W3CAnnotation(
    /** Stable UUID identifying this annotation across devices. */
    val id: String,
    /** CFI *range* for highlights/notes, CFI *point* for bookmarks (ADR 0024). */
    val cfi: String,
    /** Human-readable snippet of the annotated text; fallback for re-anchoring. */
    val textSnippet: String,
    /** Document text immediately before the snippet (W3C TextQuoteSelector `prefix`); used to
     *  disambiguate which occurrence of the snippet a highlight anchors to when the text repeats. */
    val textBefore: String = "",
    /** Document text immediately after the snippet (W3C TextQuoteSelector `suffix`); same role
     *  as [textBefore] for disambiguation. */
    val textAfter: String = "",
    /** EPUB chapter href for context during merge/navigation. */
    val chapterHref: String,
    /** Type: "HIGHLIGHT" or "BOOKMARK". */
    val type: String,
    /** Color name (e.g., "yellow", "green") for highlights; null for bookmarks. */
    val color: String? = null,
    /** Note/comment text; null if not set. */
    val note: String? = null,
    /** Title for bookmarks; null or empty for highlights. */
    val bookmarkTitle: String? = null,
    /** Device that created this annotation. */
    val originDeviceId: String,
    /** Device that last modified this annotation (for tie-breaking in LWW). */
    val lastModifiedByDeviceId: String,
    /** Milliseconds since epoch; used for last-write-wins merge. */
    val updatedAt: Long,
    /** Milliseconds since epoch; timestamp of creation. */
    val createdAt: Long,
    /** Tombstone flag; true if this annotation has been deleted. */
    val deleted: Boolean = false,
)
