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
        // The JS must respect the tintCap flag in the raster payload. Even though tintCap is
        // currently always 1 for figure annotations, the gate must remain so future callers can
        // set it to 0 if needed. Removing the `if (rf.tintCap)` guard would unconditionally
        // call tintCaptionFor even for marks explicitly flagged to skip it.
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
    fun `apply js checks adjacent figure siblings for caption when figcaption absent`() {
        // O'Reilly EPUBs place the caption as a <p> sibling of <figure>, not inside it.
        // The fix adds previousElementSibling + nextElementSibling checks on <figure> so the
        // caption is found even when querySelector('figcaption') returns null.
        // Reverting to a figcaption-only or FOLLOWING-only search flips this red.
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)

        assertTrue(js.contains("previousElementSibling"), "tintCaptionFor must check the previous sibling of <figure> for a caption")
        assertTrue(js.contains("nextElementSibling"), "tintCaptionFor must check the next sibling of <figure> for a caption")
        assertTrue(js.contains("getElementsByTagName('figcaption')"), "tintCaptionFor must use getElementsByTagName as namespace-safe fallback for XHTML EPUBs")
        // siblingCaption must be factored into its own function to avoid duplicate sibling-walk
        // logic and to reuse it in nearestCaptionBlock. Flattening it back inline would remove
        // the named function and break the assertion below.
        assertTrue(js.contains("function siblingCaption("), "sibling walk must be factored into siblingCaption() so nearestCaptionBlock reuses it without duplicating the walk")
        // nearestCaptionBlock must use siblingCaption (sibling walking), NOT querySelectorAll.
        // querySelectorAll across an ancestor section can false-positive match prose like
        // "Figure 5-4 shows the result..." before the actual caption is found. Reverting to
        // querySelectorAll in the fallback flips this red.
        assertTrue(!js.contains("parent.querySelectorAll"), "nearestCaptionBlock must not use querySelectorAll — sibling walking via siblingCaption avoids false-positive matches on prose paragraphs that start with 'Figure N'")
    }

    @Test
    fun `apply js checks h6 inside figure before falling back to sibling scan`() {
        // O'Reilly EPUBs use <h6> (not <figcaption>) as the caption element, placed inside the
        // <figure><div class="figure"> wrapper alongside the <img>. Without this check the code
        // fell back to siblingCaption(<figure>), which false-positived on the prose reference
        // paragraph preceding the figure ("Figure 5-4 shows the result…"). The fix looks for
        // h6/h5/h4/h3 inside the <figure> before checking siblings. Reverting removes the heading
        // scan and lets the prose paragraph be tinted instead of the actual caption.
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)
        assertTrue(js.contains("getElementsByTagName('h6')"), "tintCaptionFor must scan inside <figure> for h6 (O'Reilly caption style) before falling back to siblings")
        assertTrue(js.contains("getElementsByTagName('h5')"), "heading scan inside figure must include h5")
        assertTrue(js.contains("getElementsByTagName('h4')"), "heading scan inside figure must include h4")
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
