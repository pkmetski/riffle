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
)
