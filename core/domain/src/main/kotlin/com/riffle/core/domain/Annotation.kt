package com.riffle.core.domain

import kotlinx.serialization.Serializable

data class Annotation(
    val id: String,
    val sourceId: String,
    val itemId: String,
    val type: String,
    val cfi: String,
    val color: String,
    val note: String?,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val chapterHref: String,
    val spineIndex: Int,
    val progression: Double,
    val bookmarkTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val embeddedFigures: List<EmbeddedFigure>? = null,
    val imageHref: String? = null,
    val imageSvg: String? = null,
    val imageBytes: String? = null,
    /**
     * Computed `font-family` at the annotation's source range, captured at creation time so the
     * Annotations View renders each excerpt in the origin's face (issue #484). Nullable because
     * rows created before this field existed (and W3C sync ingest) have no value.
     */
    val originFontFamily: String? = null,
)

/**
 * A figure enclosed by a TYPE_HIGHLIGHT annotation's range, or the standalone image backing a
 * TYPE_IMAGE annotation. Persisted as a JSON array on [com.riffle.core.database.AnnotationEntity.embeddedFigures].
 *
 * @property href Href of the source image, when the figure is a raster/bitmap asset.
 * @property svg Inline SVG markup, when the figure is an SVG.
 * @property caption Figure caption text, empty when the source has none.
 * @property order Position of this figure within the annotation's range, for stable display order.
 */
@Serializable
data class EmbeddedFigure(
    val href: String?,
    val svg: String?,
    val caption: String,
    val order: Int,
    /**
     * Data URI (`data:image/…;base64,…`) of the raster figure, captured via canvas at
     * selection-change time. Present when a text-highlight range crossed a raster figure — lets
     * the Highlights-mode elided reader render it inline without needing the source Publication.
     */
    val imageBytes: String? = null,
    /**
     * Position of this figure within the highlight's readable-text stream, measured in the same
     * char-count model as [com.riffle.core.domain.countBodyChars] — i.e. blank-text-node-aware and
     * counting a void `<img>` as zero chars. Captured at create-time from
     * `findEnclosedFiguresInHtml` and used by the annotations panel / elided reader to split the
     * highlight's `textSnippet` at the figure's true position so the graph renders in-flow between
     * the text before and after it (fix 2026-07-09). Null on annotations created before this
     * field existed — callers fall back to "figures render after all text", the v1 behaviour.
     */
    val charOffset: Long? = null,
)
