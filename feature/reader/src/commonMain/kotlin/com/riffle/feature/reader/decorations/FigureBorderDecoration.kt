package com.riffle.feature.reader.decorations

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.normalizeCaptionText
import com.riffle.feature.reader.toCssRgba

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
object FigureBorderDecoration {

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
            // !important is required because some publishers (e.g. Wiley's WileyTemplate) ship a
            // CSS reset like `#sbo-rt-content img { outline: 0 }` — an ID-selector rule that
            // beats our attribute selector on specificity and silently nukes the border. Verified
            // against Influence Without Authority 3e.
            "$selector { outline: $OUTLINE_WIDTH_CSS solid ${ref.color} !important; " +
                "outline-offset: $OUTLINE_OFFSET_CSS !important; }"
        }

    /**
     * Per-figure raster match — filename suffix (for CSS `[src$=…]` matching), the annotation's
     * CSS-ready color, and whether the annotation carries a note (drives the note-glyph badge).
     * [tintCaption] is true unless Readium's decoration fully covers the caption (i.e. the full
     * caption text is in the selection) — that is the only case where the CSS `tintCaptionFor`
     * call would be redundant. Partial coverage keeps [tintCaption] true so the entire caption
     * element is visually styled (Readium only decorates the selected characters).
     */
    data class RasterMark(
        val filename: String,
        val color: String,
        val hasNote: Boolean,
        val tintCaption: Boolean = true,
    )

    fun buildRasterMarks(annotations: List<Annotation>): List<RasterMark> {
        data class Ref(
            val href: String,
            val color: String,
            val hasNote: Boolean,
            val updatedAt: Long,
            val tintCaption: Boolean,
        )
        val refs = mutableListOf<Ref>()
        for (a in annotations) {
            val hasNote = !a.note.isNullOrBlank()
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE ->
                    a.imageHref?.let { refs += Ref(it, a.color, hasNote, a.updatedAt, tintCaption = true) }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { fig ->
                    fig.href?.let {
                        refs += Ref(it, a.color, hasNote, a.updatedAt, tintCaption = !highlightOverlapsCaption(a, fig))
                    }
                }
            }
        }
        return refs.groupBy { hrefFilename(it.href) }
            .mapValues { (_, group) ->
                group.maxByOrNull { it.updatedAt }!!.copy(tintCaption = group.all { it.tintCaption })
            }
            .values
            .map {
                RasterMark(
                    filename = hrefFilename(it.href),
                    color = HighlightColor.fromToken(it.color).argb.toCssRgba(),
                    hasNote = it.hasNote,
                    tintCaption = it.tintCaption,
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
     * True only when Readium's highlight decoration FULLY covers the figure's caption — meaning
     * the CSS `tintCaptionFor` pass would be redundant for every character of the caption text.
     * False for any partial overlap (selection enters but does not span the whole caption): in
     * that case the CSS tint MUST fire so the entire caption element is visually styled, matching
     * the reader's expectation that "the legend underneath" appears highlighted as part of the
     * annotation.
     *
     * The two conditions that constitute full coverage:
     *  1. Non-blank caption: `normalizedSnippet` contains the full caption string, OR the
     *     post-figure text in the snippet starts with the full caption (selection extends past it).
     *  2. Blank caption (the shape written by `EpubReaderViewModel.onFigureLongPress` /
     *     `CaptionHighlightUpgrader` — no `<figcaption>` element): the snippet itself IS the
     *     caption, detected by the canonical caption-label prefix (Figure/Table + digit).
     *
     * Partial overlaps (selection enters the caption mid-way, or only the first N characters of
     * the caption are selected) return false so the CSS tint covers the full element.
     */
    private fun highlightOverlapsCaption(annotation: Annotation, figure: EmbeddedFigure): Boolean {
        val normalizedSnippet = normalizeCaptionText(annotation.textSnippet)
        if (figure.caption.isBlank()) {
            // Blank caption = caption-highlight shape: the textSnippet IS the caption starting
            // at the figure label. The prefix check confirms this without a charOffset.
            return CAPTION_HIGHLIGHT_PREFIX_REGEX.containsMatchIn(normalizedSnippet)
        }
        val normalizedCaption = normalizeCaptionText(figure.caption)
        // Full caption is contained in the snippet → Readium decoration covers it entirely.
        if (normalizedSnippet.contains(normalizedCaption)) return true
        val figureOffset = figure.charOffset ?: return false
        val snippetFromFigure = snippetFromOffset(annotation.textSnippet, figureOffset)
        if (snippetFromFigure.isEmpty()) return false
        // Post-figure text starts with the full caption → Readium covers the caption completely.
        return snippetFromFigure.startsWith(normalizedCaption)
    }

    private fun snippetFromOffset(raw: String, offset: Long): String =
        normalizeCaptionText(raw.drop(offset.coerceAtMost(raw.length.toLong()).toInt()))

    private const val CAPTION_KEYWORDS = "Figure|Fig\\.?|Table|Chart"

    private val CAPTION_HIGHLIGHT_PREFIX_REGEX =
        Regex("^\\s*($CAPTION_KEYWORDS)\\s+\\d", RegexOption.IGNORE_CASE)

    /**
     * One entry per SVG annotation covering the current document. Newest-wins by `updatedAt` when
     * two annotations reference the same SVG (same fingerprint).
     */
    data class SvgMatch(
        val fingerprint: String,
        val color: String,
        val hasNote: Boolean = false,
        val tintCaption: Boolean = true,
    )

    fun buildSvgMatches(annotations: List<Annotation>): List<SvgMatch> {
        data class Ref(
            val fingerprint: String,
            val color: String,
            val hasNote: Boolean,
            val updatedAt: Long,
            val tintCaption: Boolean,
        )

        val refs = mutableListOf<Ref>()
        for (a in annotations) {
            val hasNote = !a.note.isNullOrBlank()
            when (a.type) {
                AnnotationEntity.TYPE_IMAGE -> a.imageSvg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                    refs += Ref(it, a.color, hasNote, a.updatedAt, tintCaption = true)
                }
                AnnotationEntity.TYPE_HIGHLIGHT -> a.embeddedFigures?.forEach { figure ->
                    figure.svg?.take(SVG_FINGERPRINT_PREFIX_LEN)?.let {
                        refs += Ref(it, a.color, hasNote, a.updatedAt, tintCaption = !highlightOverlapsCaption(a, figure))
                    }
                }
            }
        }

        return refs.groupBy { it.fingerprint }
            .mapValues { (_, group) ->
                group.maxByOrNull { it.updatedAt }!!.copy(tintCaption = group.all { it.tintCaption })
            }
            .values
            .map {
                SvgMatch(
                    fingerprint = it.fingerprint,
                    color = HighlightColor.fromToken(it.color).argb.toCssRgba(),
                    hasNote = it.hasNote,
                    tintCaption = it.tintCaption,
                )
            }
    }
}
