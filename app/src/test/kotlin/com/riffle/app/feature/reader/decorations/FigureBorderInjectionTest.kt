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
            "apply JS should query figcaption elements with the tint marker",
            js.contains("figcaption[data-riffle-fig-tint]"),
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
            "apply JS should target the first child figcaption",
            js.contains("querySelector('figcaption')") ||
                js.contains("querySelector(\"figcaption\")"),
        )
        assertTrue(
            "apply JS should set backgroundColor to the raster mark's color",
            js.contains("52,211,153"),
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
    }
}
