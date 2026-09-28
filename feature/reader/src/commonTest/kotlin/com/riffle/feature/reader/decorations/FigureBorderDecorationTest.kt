package com.riffle.feature.reader.decorations

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FigureBorderDecorationTest {

    @Test
    fun `newest annotation wins when two cover the same figure`() {
        val older = imageAnnotation(id = "a", imageHref = "g.png", color = "yellow", updatedAt = 100)
        val newer = imageAnnotation(id = "b", imageHref = "g.png", color = "green", updatedAt = 200)

        val rules = FigureBorderDecoration.buildCssRules(listOf(older, newer))

        assertEquals(1, rules.count { it.contains("g.png") })
        val rule = rules.single { it.contains("g.png") }
        // green = 0x8034D399 -> rgb(52,211,153); reverting maxByOrNull{updatedAt} to e.g. minByOrNull
        // or firstOrNull would flip this to yellow (251,191,36) and fail.
        assertTrue(rule.contains("52,211,153"))
        assertFalse(rule.contains("251,191,36"))
    }

    @Test
    fun `emits one rule per distinct figure`() {
        val a = imageAnnotation(id = "a", imageHref = "one.png", color = "yellow")
        val b = imageAnnotation(id = "b", imageHref = "two.png", color = "green")

        val rules = FigureBorderDecoration.buildCssRules(listOf(a, b))

        assertEquals(2, rules.size)
    }

    @Test
    fun `TYPE_HIGHLIGHT with embedded figures produces border rules`() {
        val hl = highlightAnnotation(
            id = "h1",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = "g.png", svg = null, caption = "cap", order = 0)),
        )

        val rules = FigureBorderDecoration.buildCssRules(listOf(hl))

        assertEquals(1, rules.size)
        assertTrue(rules.single().contains("g.png"))
        // yellow = 0x80FBBF24 -> rgb(251,191,36)
        assertTrue(rules.single().contains("251,191,36"))
    }

    @Test
    fun `pure SVG annotation produces no CSS rule but does produce an SVG match`() {
        val svgImage = imageAnnotation(id = "s1", imageHref = null, imageSvg = "<svg id=\"a\"></svg>", color = "yellow")
        val svgEmbedded = highlightAnnotation(
            id = "h2",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = null, svg = "<svg id=\"b\"></svg>", caption = "cap", order = 0)),
        )

        val rules = FigureBorderDecoration.buildCssRules(listOf(svgImage, svgEmbedded))
        val svgMatches = FigureBorderDecoration.buildSvgMatches(listOf(svgImage, svgEmbedded))

        // buildCssRules stays raster-only — reverting it to also process SVG would flip this red.
        assertTrue(rules.isEmpty())
        // buildSvgMatches now covers SVG — reverting SVG support would flip this red.
        assertEquals(2, svgMatches.size)
        assertTrue(svgMatches.any { it.fingerprint.contains("id=\"a\"") })
        assertTrue(svgMatches.any { it.fingerprint.contains("id=\"b\"") })
    }

    @Test
    fun `SVG matches newest wins when two annotations reference the same svg`() {
        val svg = "<svg><rect x=\"1\"/></svg>"
        val older = imageAnnotation(id = "a", imageHref = null, imageSvg = svg, color = "yellow", updatedAt = 100)
        val newer = imageAnnotation(id = "b", imageHref = null, imageSvg = svg, color = "green", updatedAt = 200)

        val matches = FigureBorderDecoration.buildSvgMatches(listOf(older, newer))

        assertEquals(1, matches.size)
        // green rgb — reverting maxByOrNull{updatedAt} would flip this to yellow (251,191,36).
        assertTrue(matches.single().color.contains("52,211,153"))
    }

    @Test
    fun `rule marks outline as important to beat publisher CSS resets`() {
        // Wiley (WileyTemplate) and other big-publisher stylesheets ship `#sbo-rt-content img {
        // outline: 0 }` — an ID-selector reset that outranks our attribute selector on
        // specificity and silently drops the border. Reverting to a non-!important rule reproduces
        // the "no border on annotated figure" bug in Influence Without Authority 3e.
        val a = imageAnnotation(id = "a", imageHref = "n01f001.gif", color = "blue")

        val rule = FigureBorderDecoration.buildCssRules(listOf(a)).single()

        assertTrue(rule.contains("outline: 2px solid") && rule.contains("!important"), "outline must be !important")
    }

    @Test
    fun `TYPE_IMAGE marks request caption tint but TYPE_HIGHLIGHT marks do not`() {
        // Post-2026-07-14 rule (caption-highlight upgrade): TYPE_HIGHLIGHT annotations whose
        // range already covers the caption receive their tint from Readium's normal decoration
        // pipeline. Firing the render-side tintCaptionFor pass for them again would double-paint.
        // TYPE_IMAGE annotations still need the render-side tint because their caption isn't a
        // real annotated span. Reverting the tintCaption plumbing flips this red.
        val legacyImage = imageAnnotation(id = "img", imageHref = "g.png", color = "yellow")
        // Caption-highlight shape: textSnippet contains the figure's caption text → Readium's
        // highlight decoration paints the caption span → skip the CSS tint (would double-paint).
        val captionHighlight = highlightAnnotation(
            id = "hl",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = "g2.png", svg = null, caption = "cap", order = 0)),
            textSnippet = "cap",
        )

        val rasters = FigureBorderDecoration.buildRasterMarks(listOf(legacyImage, captionHighlight))
        assertTrue(rasters.single { it.filename == "g.png" }.tintCaption)
        assertFalse(rasters.single { it.filename == "g2.png" }.tintCaption)

        val svgImage = imageAnnotation(id = "img2", imageSvg = "<svg id=\"i\"></svg>", color = "yellow")
        val svgHighlight = highlightAnnotation(
            id = "hl2",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = null, svg = "<svg id=\"h\"></svg>", caption = "cap", order = 0)),
            textSnippet = "cap",
        )
        val svgs = FigureBorderDecoration.buildSvgMatches(listOf(svgImage, svgHighlight))
        assertTrue(svgs.single { it.fingerprint.contains("id=\"i\"") }.tintCaption)
        assertFalse(svgs.single { it.fingerprint.contains("id=\"h\"") }.tintCaption)
    }

    @Test
    fun `TYPE_HIGHLIGHT ending inside figcaption keeps CSS caption tint for unselected tail`() {
        // Regression: when a selection spans prose → figure → PARTIAL caption, the CSS tint must
        // still fire so the ENTIRE caption element is visually styled. Before the regression,
        // highlightOverlapsCaption returned true for partial overlaps, suppressing the tint and
        // leaving the unselected tail of the caption un-highlighted. Readium only decorates the
        // selected portion; CSS tint is the only mechanism that can style the rest of the element.
        val beforeCaption = "The surrounding selection includes the diagram. "
        val partialCaption = "Figure 3.1: At the beginning, a tactical approach"
        val caption = "$partialCaption to programming will make progress more quickly."
        val highlight = highlightAnnotation(
            id = "hl-partial-caption",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = caption,
                    order = 0,
                    charOffset = beforeCaption.length.toLong(),
                ),
            ),
            textSnippet = beforeCaption + partialCaption,
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "partial-caption selection must keep CSS tint so the entire legend element appears highlighted")
    }

    @Test
    fun `TYPE_HIGHLIGHT starting inside figcaption keeps CSS caption tint for unselected head`() {
        // When a selection starts mid-caption, the CSS tint must still fire so the entire caption
        // element is styled. The selected tail is covered by Readium's decoration; the unselected
        // head ("Figure 3.1: At the beginning, a ") only gets colour from the CSS tint.
        val selectedCaptionTail = "tactical approach to programming will make progress more quickly."
        val caption = "Figure 3.1: At the beginning, a $selectedCaptionTail"
        val highlight = highlightAnnotation(
            id = "hl-caption-tail",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = caption,
                    order = 0,
                    charOffset = 0,
                ),
            ),
            textSnippet = "$selectedCaptionTail Following prose.",
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "mid-caption selection must keep CSS tint so the unselected head of the caption element is also highlighted")
    }

    @Test
    fun `separate caption and diagram annotations do not double tint the caption`() {
        val caption = "Figure 3.1: A tactical approach makes progress quickly."
        val captionHighlight = highlightAnnotation(
            id = "caption",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = "g.png", svg = null, caption = caption, order = 0)),
            updatedAt = 100,
            textSnippet = caption,
        )
        val diagramHighlight = imageAnnotation(
            id = "diagram",
            imageHref = "g.png",
            color = "yellow",
            updatedAt = 200,
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(captionHighlight, diagramHighlight)).single()

        assertFalse(mark.tintCaption)

        val svg = "<svg id=\"diagram\"></svg>"
        val svgCaptionHighlight = highlightAnnotation(
            id = "svg-caption",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = null, svg = svg, caption = caption, order = 0)),
            updatedAt = 100,
            textSnippet = caption,
        )
        val svgDiagramHighlight = imageAnnotation(
            id = "svg-diagram",
            imageSvg = svg,
            color = "yellow",
            updatedAt = 200,
        )

        val svgMark = FigureBorderDecoration.buildSvgMatches(listOf(svgCaptionHighlight, svgDiagramHighlight)).single()

        assertFalse(svgMark.tintCaption)
    }

    @Test
    fun `TYPE_HIGHLIGHT with blank caption and prose-before-figure snippet keeps CSS caption tint`() {
        // Real-world case: book uses <p class="caption"> instead of <figcaption>. The JS stash
        // stores caption="" because there's no <figcaption> element. The selection spans prose →
        // figure → partial caption ("Figure N:"). The CSS tint must fire because it is the only
        // mechanism that styles the entire <p class="caption"> element. Readium's decoration only
        // covers the selected portion; the CSS tint (with !important) ensures the whole legend
        // element is visually associated with the annotation.
        val highlight = highlightAnnotation(
            id = "hl-blank-cap",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = "",
                    order = 0,
                    charOffset = 100L,
                ),
            ),
            textSnippet = "You will quickly recover the cost of the initial investment. Figure 3.1 illustrates this phenomenon. Figure 3.1: At the beginning, a tactical approach",
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "blank-caption figure whose snippet crosses into caption label must keep CSS tint (legend must appear fully highlighted)")
    }

    @Test
    fun `TYPE_HIGHLIGHT with blank caption and prose-only snippet keeps CSS caption tint`() {
        // Companion: when the snippet does NOT reach the caption element (no caption label after
        // the figure position), the CSS tint must fire so the caption gets any colour at all.
        val highlight = highlightAnnotation(
            id = "hl-no-cap",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = "",
                    order = 0,
                    charOffset = 80L,
                ),
            ),
            textSnippet = "This paragraph precedes the figure and the next paragraph follows it. The image shows",
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "blank-caption figure with prose-only snippet must keep CSS tint")
    }

    @Test
    fun `TYPE_HIGHLIGHT with null charOffset and snippet ending inside figcaption keeps CSS caption tint`() {
        // JS-stash-only figures arrive with charOffset=null. Without charOffset we cannot
        // determine whether the full caption is covered, so we conservatively keep the CSS tint
        // to ensure the entire figcaption element is visually styled. Readium covers the selected
        // portion; the CSS tint (with !important) styles the un-selected tail.
        val beforeCaption = "You will quickly recover the cost of the initial investment. Figure 3.1 illustrates this phenomenon. "
        val partialCaption = "Figure 3.1: At the beginning, a tactical approach"
        val caption = "$partialCaption to programming will make progress more quickly. Note: this figure."
        val highlight = highlightAnnotation(
            id = "hl-null-offset",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = caption,
                    order = 0,
                    charOffset = null,
                ),
            ),
            textSnippet = beforeCaption + partialCaption,
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "null-charOffset partial-caption snippet must keep CSS tint so the entire legend element is highlighted")
    }

    @Test
    fun `TYPE_HIGHLIGHT with null charOffset and snippet excluding figcaption keeps CSS caption tint`() {
        // Companion to the above: when the snippet does NOT reach the figcaption (no suffix/prefix
        // overlap), the CSS tint must still fire so the figcaption gets any colour at all.
        val highlight = highlightAnnotation(
            id = "hl-no-caption",
            color = "yellow",
            embedded = listOf(
                EmbeddedFigure(
                    href = "g.png",
                    svg = null,
                    caption = "Figure 3.1: A completely unrelated caption text here.",
                    order = 0,
                    charOffset = null,
                ),
            ),
            textSnippet = "This paragraph mentions the diagram but does not include its caption.",
        )

        val mark = FigureBorderDecoration.buildRasterMarks(listOf(highlight)).single()

        assertTrue(mark.tintCaption, "null-charOffset snippet not entering figcaption must keep CSS tint")
    }

    @Test
    fun `TYPE_HIGHLIGHT whose range excludes the figcaption keeps CSS caption tint`() {
        // Regression pin for the 2026-07-14 review finding: a legacy text-highlight of body
        // prose that happens to enclose a figure (PR #533 shape) does NOT cover the
        // <figcaption> text — its textSnippet is the surrounding paragraphs, not the caption.
        // Before this fix all TYPE_HIGHLIGHT-with-embeddedFigures got tintCaption=false and
        // the CSS tint was suppressed, silently regressing every pre-caption-highlight user's
        // figcaption tint. Now we set tintCaption=false ONLY when the highlight's textSnippet
        // actually contains the caption text.
        val bodyProseHighlight = highlightAnnotation(
            id = "hl-body",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = "g.png", svg = null, caption = "Figure 1", order = 0)),
            textSnippet = "This paragraph precedes the figure and the next paragraph follows it.",
        )
        val marks = FigureBorderDecoration.buildRasterMarks(listOf(bodyProseHighlight))
        assertTrue(marks.single().tintCaption, "text-highlight whose range excludes the caption must keep the CSS caption tint")
    }

    @Test
    fun `caption-highlight with blank figure caption still suppresses CSS tint`() {
        // Regression pin for the "visually duplicated" bug (2026-07-14): the caption-highlight
        // path in EpubReaderViewModel.onFigureLongPress writes EmbeddedFigure(caption="") to keep
        // the elided-view renderer clean, so highlightCoversCaption must NOT fall back to
        // tintCaption=true when the annotation's textSnippet is itself the caption. Otherwise
        // Readium's highlight decoration and the CSS tint both paint the <figcaption>, stacking
        // to ~2x opacity. Detects the caption-highlight shape via the same canonical caption
        // prefix (Figure|Fig.|Table|Chart + digit) used by HighlightsPublicationFactory.
        val captionHighlightRaster = highlightAnnotation(
            id = "hl-raster",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = "g.png", svg = null, caption = "", order = 0)),
            textSnippet = "Figure 20.2: The original code for allocating new space at the end of a Buffer.",
        )
        val rasters = FigureBorderDecoration.buildRasterMarks(listOf(captionHighlightRaster))
        assertFalse(rasters.single().tintCaption, "caption-highlight with blank figure.caption must not request CSS tint (would double-paint)")

        val captionHighlightSvg = highlightAnnotation(
            id = "hl-svg",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = null, svg = "<svg id=\"c\"></svg>", caption = "", order = 0)),
            textSnippet = "Table 3: Comparative results across all six datasets.",
        )
        val svgs = FigureBorderDecoration.buildSvgMatches(listOf(captionHighlightSvg))
        assertFalse(svgs.single().tintCaption, "SVG caption-highlight with blank figure.caption must not request CSS tint")

        // Legacy decorative-figure shape (blank caption, non-caption textSnippet) still keeps the
        // CSS tint — the prefix check protects the pre-caption-highlight body-prose case.
        val legacyDecorative = highlightAnnotation(
            id = "hl-legacy",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = "d.png", svg = null, caption = "", order = 0)),
            textSnippet = "This paragraph discusses the surrounding topic and the diagram is decorative.",
        )
        val legacyRasters = FigureBorderDecoration.buildRasterMarks(listOf(legacyDecorative))
        assertTrue(legacyRasters.single().tintCaption, "legacy decorative-figure highlight (no caption prefix in snippet) still requests CSS tint")
    }

    @Test
    fun `rule includes the annotation color`() {
        val a = imageAnnotation(id = "a", imageHref = "g.png", color = "blue")

        val rules = FigureBorderDecoration.buildCssRules(listOf(a))

        // blue = 0x8038BDF8 -> rgb(56,189,248); reverting the color plumbing to a hardcoded
        // default (e.g. always yellow) would flip this assertion red.
        assertTrue(rules.single().contains("56,189,248"))
    }

    private fun imageAnnotation(
        id: String,
        imageHref: String? = null,
        imageSvg: String? = null,
        color: String = "yellow",
        updatedAt: Long = 0L,
    ): Annotation = baseAnnotation(
        id = id,
        type = AnnotationEntity.TYPE_IMAGE,
        color = color,
        updatedAt = updatedAt,
        imageHref = imageHref,
        imageSvg = imageSvg,
    )

    private fun highlightAnnotation(
        id: String,
        color: String = "yellow",
        embedded: List<EmbeddedFigure>? = null,
        updatedAt: Long = 0L,
        textSnippet: String = "",
    ): Annotation = baseAnnotation(
        id = id,
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        color = color,
        updatedAt = updatedAt,
        embeddedFigures = embedded,
        textSnippet = textSnippet,
    )

    private fun baseAnnotation(
        id: String,
        type: String,
        color: String,
        updatedAt: Long,
        imageHref: String? = null,
        imageSvg: String? = null,
        embeddedFigures: List<EmbeddedFigure>? = null,
        textSnippet: String = "",
    ): Annotation = Annotation(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = type,
        cfi = "epubcfi(/6/2!/dummy)",
        color = color,
        note = null,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "chA.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = updatedAt,
        embeddedFigures = embeddedFigures,
        imageHref = imageHref,
        imageSvg = imageSvg,
    )
}
