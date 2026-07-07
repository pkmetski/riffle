package com.riffle.app.feature.reader.decorations

import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.HighlightColor

/**
 * Builds the CSS that draws a coloured border around figures covered by an annotation, so an
 * annotated diagram/chart/image stands out in the normal reading flow (not just in the
 * annotations panel).
 *
 * v1 raster-only limitation: figures are matched by an `img[src$="…"]` suffix selector against
 * the annotation's [Annotation.imageHref] ([AnnotationEntity.TYPE_IMAGE]) or an embedded figure's
 * [com.riffle.core.domain.EmbeddedFigure.href] ([AnnotationEntity.TYPE_HIGHLIGHT]). Pure-SVG
 * figures (`imageHref == null && imageSvg != null`, or an [com.riffle.core.domain.EmbeddedFigure]
 * with `href == null && svg != null`) have no stable DOM selector to hang a border off today —
 * inline SVG has no `src` attribute, and we don't yet thread an `elementId` onto the persisted
 * annotation to select it by id. Those annotations still exist in the DB and still show up in the
 * annotations panel / Highlights-mode view; only the in-reader border is skipped for them. See
 * Task 8 in docs/superpowers/plans/2026-07-07-figure-annotations.md.
 */
internal object FigureBorderDecoration {

    private const val OUTLINE_WIDTH_CSS = "2px"
    private const val OUTLINE_OFFSET_CSS = "2px"

    /**
     * One CSS rule per distinct raster figure href referenced by [annotations]. When two or more
     * annotations reference the same figure, the one with the greatest [Annotation.updatedAt]
     * wins — matches the "newest wins" rule used elsewhere for overlapping annotation state.
     */
    fun buildCssRules(annotations: List<Annotation>): List<String> {
        data class FigureRef(val href: String, val color: String, val updatedAt: Long)

        val refs = mutableListOf<FigureRef>()
        for (a in annotations) {
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE -> a.imageHref?.let { href ->
                    refs += FigureRef(href, a.color, a.updatedAt)
                }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { figure ->
                    figure.href?.let { href ->
                        refs += FigureRef(href, a.color, a.updatedAt)
                    }
                }
            }
        }

        return refs.groupBy { it.href }
            .mapValues { (_, group) -> group.maxByOrNull { it.updatedAt }!! }
            .values
            .sortedBy { it.href }
            .map { ref ->
                val cssColor = HighlightColor.fromToken(ref.color).argb.toCssRgba()
                val selector = "img[src\$=\"${ref.href}\"]"
                "$selector { outline: $OUTLINE_WIDTH_CSS solid $cssColor; outline-offset: $OUTLINE_OFFSET_CSS; }"
            }
    }
}
