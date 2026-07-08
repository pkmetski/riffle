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
    fun buildCssRules(annotations: List<Annotation>): List<String> = buildRasterMarks(annotations)
        .sortedBy { it.filename }
        .map { ref ->
            val selector = "img[src\$=\"${ref.filename}\"]"
            "$selector { outline: $OUTLINE_WIDTH_CSS solid ${ref.color}; outline-offset: $OUTLINE_OFFSET_CSS; }"
        }

    /**
     * Per-figure raster match — filename suffix (for CSS `[src$=…]` matching), the annotation's
     * CSS-ready color, and whether the annotation carries a note (drives the note-glyph badge).
     */
    internal data class RasterMark(val filename: String, val color: String, val hasNote: Boolean)

    fun buildRasterMarks(annotations: List<Annotation>): List<RasterMark> {
        data class Ref(val href: String, val color: String, val hasNote: Boolean, val updatedAt: Long)
        val refs = mutableListOf<Ref>()
        for (a in annotations) {
            val hasNote = !a.note.isNullOrBlank()
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE -> a.imageHref?.let { refs += Ref(it, a.color, hasNote, a.updatedAt) }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { fig ->
                    fig.href?.let { refs += Ref(it, a.color, hasNote, a.updatedAt) }
                }
            }
        }
        return refs.groupBy { hrefFilename(it.href) }
            .mapValues { (_, group) -> group.maxByOrNull { it.updatedAt }!! }
            .values
            .map {
                RasterMark(
                    filename = hrefFilename(it.href),
                    color = HighlightColor.fromToken(it.color).argb.toCssRgba(),
                    hasNote = it.hasNote,
                )
            }
    }

    /**
     * Filename component of a captured imageHref — the last path segment after '/'. Falls back
     * to the whole href if there's no '/'. Escapes double-quotes so the value is safe to embed
     * inside a `[src$="…"]` selector.
     */
    private fun hrefFilename(href: String): String {
        val trimmed = href.substringBefore('?').substringBefore('#')
        val slash = trimmed.lastIndexOf('/')
        val name = if (slash >= 0) trimmed.substring(slash + 1) else trimmed
        return name.replace("\"", "\\\"")
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
    internal data class SvgMatch(val fingerprint: String, val color: String, val hasNote: Boolean = false)

    fun buildSvgMatches(annotations: List<Annotation>): List<SvgMatch> {
        data class Ref(val fingerprint: String, val color: String, val hasNote: Boolean, val updatedAt: Long)

        val refs = mutableListOf<Ref>()
        for (a in annotations) {
            val hasNote = !a.note.isNullOrBlank()
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE -> a.imageSvg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                    refs += Ref(it, a.color, hasNote, a.updatedAt)
                }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { figure ->
                    figure.svg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                        refs += Ref(it, a.color, hasNote, a.updatedAt)
                    }
                }
            }
        }

        return refs.groupBy { it.fingerprint }
            .mapValues { (_, group) -> group.maxByOrNull { it.updatedAt }!! }
            .values
            .map {
                SvgMatch(
                    fingerprint = it.fingerprint,
                    color = HighlightColor.fromToken(it.color).argb.toCssRgba(),
                    hasNote = it.hasNote,
                )
            }
    }
}
