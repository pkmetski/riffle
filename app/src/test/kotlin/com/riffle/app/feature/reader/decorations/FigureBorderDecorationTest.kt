package com.riffle.app.feature.reader.decorations

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.EmbeddedFigure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue("outline must be !important", rule.contains("outline: 2px solid") && rule.contains("!important"))
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
        assertTrue(
            "text-highlight whose range excludes the caption must keep the CSS caption tint",
            marks.single().tintCaption,
        )
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
