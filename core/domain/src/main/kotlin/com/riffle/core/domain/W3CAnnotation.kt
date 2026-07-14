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
    /** Zero-based spine position of the containing chapter — cross-chapter sort key for the panel.
     *  Derivable from [cfi] but not part of the W3C spec, so it rides along as a Riffle extension
     *  to keep the sort order stable across a WebDAV round-trip (see ADR 0038 / AnnotationDao
     *  observeAnnotationsByPosition). Defaults to 0 for backward-compat with files written before
     *  the extension existed. */
    val spineIndex: Int = 0,
    /** Within-chapter fractional offset (0.0–1.0) — secondary sort key. Same round-trip rationale
     *  as [spineIndex]. Defaults to 0.0 for backward-compat. */
    val progression: Double = 0.0,
    /** Figures enclosed by a TYPE_HIGHLIGHT annotation's range, carried as `riffle:image` Web
     *  Annotation bodies alongside the text body. Null on TYPE_BOOKMARK and on TYPE_IMAGE rows
     *  (which use [imageHref]/[imageSvg] directly instead). */
    val embeddedFigures: List<EmbeddedFigure>? = null,
    /** Href of the source image for a TYPE_IMAGE annotation, carried as a `riffle:image` body. */
    val imageHref: String? = null,
    /** Inline SVG markup for a TYPE_IMAGE annotation, when the figure is an SVG. */
    val imageSvg: String? = null,
    /** Data URI of the captured raster bytes for a TYPE_IMAGE annotation. Carried as an extension
     *  field on the `riffle:image` body so a sync round-trip doesn't drop the local raster. Null
     *  when the source didn't rasterize (or the file was written by an older peer). */
    val imageBytes: String? = null,
)
