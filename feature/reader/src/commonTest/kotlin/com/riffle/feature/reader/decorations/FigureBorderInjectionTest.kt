package com.riffle.feature.reader.decorations

import kotlin.test.Test
import kotlin.test.assertTrue


class FigureBorderInjectionTest {

    @Test
    fun `apply js clears stale figcaption tints before applying`() {
        val js = figureBorderApplyJs(
            cssRules = emptyList(),
            svgMatches = emptyList(),
            rasterMarks = emptyList(),
        )
        // Every apply pass must sweep figcaption[data-riffle-fig-tint] and clear the tint,
        // mirroring the SVG "always-clear-then-apply" pattern already in this file. Reverting
        // to a leave-stale-tints-in-place approach flips this red.
        assertTrue(js.contains("data-riffle-fig-tint"), "apply JS is missing the figcaption clear pass")
        assertTrue(js.contains("querySelectorAll('[data-riffle-fig-tint]')"), "apply JS clear pass must scan every tinted element (not just <figcaption>), so <p>/<div> caption fallbacks are also cleared on undo")
        assertTrue(js.contains("clearAllFigcaptionTints();"), "apply JS must invoke clearAllFigcaptionTints() every pass, not just define it")
    }

    @Test
    fun `apply js sets figcaption tint with important priority to beat publisher CSS`() {
        // Publishers (e.g. Wiley's WileyTemplate) reset colors on <p>/<figcaption> via
        // ID-scoped rules. Setting backgroundColor without 'important' loses the specificity
        // fight and the caption stays untinted. Reverting the setProperty importance flag flips
        // this red.
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)

        assertTrue(js.contains("setProperty('background-color', color, 'important')"), "tintCaptionFor must use setProperty('background-color', ..., 'important')")
    }

    @Test
    fun `apply js falls back to text-prefix caption block for non-semantic figures`() {
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)

        // For LaTeX/Kotobee/Vellum EPUBs (obfuscated class names, no <figure> wrapper), the tint
        // must fall back to finding the nearest block whose text starts with the caption prefix
        // "Figure N", "Fig. N", "Table N", "Chart N". Reverting the fallback flips this red.
        assertTrue(js.contains("(Figure|Fig\\.?|Table|Chart)"), "apply JS should define the caption-prefix regex")
        assertTrue(js.contains("function nearestCaptionBlock("), "apply JS should define the nearestCaptionBlock helper")
        assertTrue(js.contains("nearestCaptionBlock(el)"), "tintCaptionFor should call nearestCaptionBlock when semantic path fails")
    }

    @Test
    fun `apply js tints figcaption for raster image annotations`() {
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)

        // For every matched raster image, the JS must walk up to the containing <figure> (or
        // role="figure") and tint the first child <figcaption> with the annotation color.
        // Reverting the caption-tint pass flips this red.
        assertTrue(js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"), "apply JS should look for a figure ancestor via closest()")
        assertTrue(js.contains("querySelector('figcaption')"), "apply JS should target figcaption (unscoped, mirroring the persistence walker)")
        assertTrue(js.contains("52,211,153"), "apply JS should set backgroundColor to the raster mark's color")
        assertTrue(js.contains("tintCaptionFor(img, rf.color)"), "raster branch must call tintCaptionFor(img, rf.color)")
    }

    @Test
    fun `apply js gates raster caption tint on the tintCap flag`() {
        // Post-2026-07-14: TYPE_HIGHLIGHT annotations that cover a caption already emit a real
        // Readium highlight over the caption text; firing tintCaptionFor for them again would
        // double-paint. buildRasterMarks flags TYPE_IMAGE marks with tintCaption=true and
        // TYPE_HIGHLIGHT-derived marks with tintCaption=false; the JS must respect it. Reverting
        // the `if (rf.tintCap)` guard reintroduces the double-paint bug.
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "hl.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
                tintCaption = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)
        assertTrue(js.contains("\"tintCap\":0"), "tintCap flag must be encoded in the raster JSON payload")
        assertTrue(js.contains("if (rf.tintCap) tintCaptionFor(img, rf.color)"), "raster branch must gate tintCaptionFor on rf.tintCap")
    }

    @Test
    fun `apply js gates svg caption tint on the tintCap flag`() {
        val matches = listOf(
            FigureBorderDecoration.SvgMatch(
                fingerprint = "<svg id=\"chart\">",
                color = "rgba(56,189,248,0.5)",
                hasNote = false,
                tintCaption = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = matches, rasterMarks = emptyList())
        assertTrue(js.contains("\"tintCap\":0"), "tintCap flag must be encoded in the svg JSON payload")
        assertTrue(js.contains("if (matches[j].tintCap) tintCaptionFor(s, matches[j].color)"), "svg branch must gate tintCaptionFor on matches[j].tintCap")
    }

    @Test
    fun `apply js tints figcaption for svg annotations`() {
        val matches = listOf(
            FigureBorderDecoration.SvgMatch(
                fingerprint = "<svg id=\"chart\">",
                color = "rgba(56,189,248,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = matches, rasterMarks = emptyList())

        // Same treatment for inline-SVG figures — must tint the containing figcaption.
        // Reverting the SVG branch's caption tint flips this red.
        assertTrue(js.contains("56,189,248"), "apply JS should carry the svg mark's color for the caption tint")
        assertTrue(js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"), "svg branch should also invoke figure/figcaption traversal")
        assertTrue(js.contains("tintCaptionFor(s, matches[j].color)"), "svg branch must call tintCaptionFor(s, matches[j].color)")
    }
}
