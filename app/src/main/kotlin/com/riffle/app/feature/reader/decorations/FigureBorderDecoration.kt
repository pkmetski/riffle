package com.riffle.app.feature.reader.decorations

import com.riffle.app.feature.reader.toCssRgba
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.HighlightColor

/**
 * Builds the CSS + JS that draws a coloured border around figures covered by an annotation, so an
 * annotated diagram/chart/image stands out in the normal reading flow (not just in the
 * annotations panel).
 *
 * Raster figures (`imageHref != null`) get a plain CSS rule via [buildCssRules] matching by
 * `img[src$="…"]`. Inline-SVG figures (`imageSvg != null`) have no stable CSS selector — inline
 * `<svg>` carries no `src`/`href` we can match on — so they get a small JS routine emitted by
 * [buildSvgApplyJs] that walks `document.querySelectorAll('svg')`, prefix-matches each element's
 * outerHTML against the stored SVG source, and sets `outline` inline on the winner.
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

    /**
     * Number of leading characters of a `<svg>` element's `outerHTML` we use as a fingerprint to
     * match a persisted annotation's [Annotation.imageSvg] against live DOM nodes. Small enough to
     * keep the injected JSON payload compact; large enough to disambiguate two different SVGs on
     * the same page.
     */
    private const val SVG_FINGERPRINT_PREFIX_LEN = 200

    /**
     * One entry per SVG annotation covering the current document. Newest-wins by `updatedAt` when
     * two annotations reference the same SVG (same fingerprint).
     */
    internal data class SvgMatch(val fingerprint: String, val color: String)

    fun buildSvgMatches(annotations: List<Annotation>): List<SvgMatch> {
        data class Ref(val fingerprint: String, val color: String, val updatedAt: Long)

        val refs = mutableListOf<Ref>()
        for (a in annotations) {
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE -> a.imageSvg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                    refs += Ref(it, a.color, a.updatedAt)
                }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { figure ->
                    figure.svg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                        refs += Ref(it, a.color, a.updatedAt)
                    }
                }
            }
        }

        return refs.groupBy { it.fingerprint }
            .mapValues { (_, group) -> group.maxByOrNull { it.updatedAt }!! }
            .values
            .map { SvgMatch(it.fingerprint, HighlightColor.fromToken(it.color).argb.toCssRgba()) }
    }
}
