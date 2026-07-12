package com.riffle.app.feature.reader.decorations

import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(
            "apply JS is missing the figcaption clear pass",
            js.contains("data-riffle-fig-tint"),
        )
        assertTrue(
            "apply JS clear pass must scan every tinted element (not just <figcaption>), so <p>/<div> caption fallbacks are also cleared on undo",
            js.contains("querySelectorAll('[data-riffle-fig-tint]')"),
        )
        assertTrue(
            "apply JS must invoke clearAllFigcaptionTints() every pass, not just define it",
            js.contains("clearAllFigcaptionTints();"),
        )
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

        assertTrue(
            "tintCaptionFor must use setProperty('background-color', ..., 'important')",
            js.contains("setProperty('background-color', color, 'important')"),
        )
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
        assertTrue(
            "apply JS should define the caption-prefix regex",
            js.contains("(Figure|Fig\\.?|Table|Chart)"),
        )
        assertTrue(
            "apply JS should define the nearestCaptionBlock helper",
            js.contains("function nearestCaptionBlock("),
        )
        assertTrue(
            "tintCaptionFor should call nearestCaptionBlock when semantic path fails",
            js.contains("nearestCaptionBlock(el)"),
        )
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
        assertTrue(
            "apply JS should look for a figure ancestor via closest()",
            js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"),
        )
        assertTrue(
            "apply JS should target figcaption (unscoped, mirroring the persistence walker)",
            js.contains("querySelector('figcaption')"),
        )
        assertTrue(
            "apply JS should set backgroundColor to the raster mark's color",
            js.contains("52,211,153"),
        )
        assertTrue(
            "raster branch must call tintCaptionFor(img, rf.color)",
            js.contains("tintCaptionFor(img, rf.color)"),
        )
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
        assertTrue(
            "apply JS should carry the svg mark's color for the caption tint",
            js.contains("56,189,248"),
        )
        assertTrue(
            "svg branch should also invoke figure/figcaption traversal",
            js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"),
        )
        assertTrue(
            "svg branch must call tintCaptionFor(s, matches[j].color)",
            js.contains("tintCaptionFor(s, matches[j].color)"),
        )
    }
}
