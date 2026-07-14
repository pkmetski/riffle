package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rhino (org.mozilla.javascript) is not on this repo's classpath, so these are string-shape
 * assertions against the emitted JS source rather than evaluated-JS-behaviour tests. They guard
 * against silently gutting a fallback branch, a filter tag, or a dedupe/order mechanism — the
 * real behavioural coverage lives in Task 13's instrumentation harness (real WebView).
 */
class FigureCaptionWalkerTest {

    @Test
    fun `caption resolver mentions figcaption before alt before aria-label`() {
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        val figIdx = js.indexOf("figcaption")
        val altIdx = js.indexOf("'alt'")
        val ariaIdx = js.indexOf("'aria-label'")
        assertTrue("figcaption lookup missing or out of order", figIdx in 0 until altIdx)
        assertTrue("alt lookup missing or out of order", altIdx in 0 until ariaIdx)
    }

    @Test
    fun `caption resolver has text-prefix fallback for non-semantic figures`() {
        // After the <figure>/<figcaption> and alt/aria-label paths, the resolver walks up to 3
        // ancestors looking for the nearest following <p>/<div> whose text starts with
        // "Figure|Fig|Table|Chart" + digit. Covers LaTeX/Kotobee/Vellum exports with obfuscated
        // class names (e.g. "A Philosophy of Software Design 2e"). Reverting the fallback flips
        // these red — image annotations on non-semantic figures would land in the DB with an
        // empty textSnippet again and the Annotations view would render an empty caption block.
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        assertTrue(
            "caption resolver should carry the caption-prefix regex",
            js.contains("(Figure|Fig\\.?|Table|Chart)"),
        )
        assertTrue(
            "caption resolver should walk parent chain (compareDocumentPosition)",
            js.contains("compareDocumentPosition"),
        )
        // The fallback must sit AFTER the alt/aria-label paths in resolveCaption so a legitimate
        // per-image alt attribute always wins over a proximity-based heuristic that could match
        // nearby prose like "Table 3 summarizes results...".
        val ariaIdx = js.indexOf("if (aria) return aria;")
        assertTrue("resolveCaption must handle aria before falling through", ariaIdx > 0)
        val prefixCallIdx = js.indexOf("resolveTextPrefixElement(el)", ariaIdx)
        assertTrue(
            "text-prefix fallback must come after aria-label inside resolveCaption",
            prefixCallIdx > ariaIdx,
        )
    }

    @Test
    fun `resolveCaptionRange emits figcaption then text-prefix but no alt or aria`() {
        // For range resolution (2026-07-14 caption-highlight upgrade), alt/aria-label are
        // invisible attributes with no persistable text range. resolveCaptionRange picks
        // resolveFigcaptionElement first (semantic), then resolveTextPrefixElement (proximity
        // heuristic), and skips alt/aria entirely. Reverting this — e.g. reusing resolveCaption's
        // full fallback chain — would return alt-only "captions" that can't be anchored, and the
        // upgrader/onFigureLongPress would silently drop them.
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        assertTrue(js.contains("function resolveCaptionRange(el)"))
        val rangeStart = js.indexOf("function resolveCaptionRange(el)")
        val rangeBody = js.substring(rangeStart)
        assertTrue(
            "resolveCaptionRange must call resolveFigcaptionElement",
            rangeBody.contains("resolveFigcaptionElement(el)"),
        )
        assertTrue(
            "resolveCaptionRange must call resolveTextPrefixElement",
            rangeBody.contains("resolveTextPrefixElement(el)"),
        )
        // Slice at the next function boundary — resolveCaptionRange's body must not read alt or
        // aria-label. `next` is the START of `function riffleCollectTextAround` above OR
        // undefined if resolveCaptionRange is the last function; either way the slice below is
        // safe (substring from rangeStart to end of file if no boundary).
        val nextBoundary = rangeBody.indexOf("function ", startIndex = 40)
        val bodyOnly = if (nextBoundary >= 0) rangeBody.substring(0, nextBoundary) else rangeBody
        assertFalse(
            "resolveCaptionRange body must not consult alt",
            bodyOnly.contains("'alt'"),
        )
        assertFalse(
            "resolveCaptionRange body must not consult aria-label",
            bodyOnly.contains("'aria-label'"),
        )
    }

    @Test
    fun `caption resolver falls back to empty string`() {
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        assertTrue(js.contains("function resolveCaption(el)"))
        assertTrue("missing final empty-string fallback", js.contains("return \"\";"))
    }

    @Test
    fun `svg serializer uses XMLSerializer and returns null on failure`() {
        val js = FigureCaptionWalker.SVG_SERIALIZER_JS
        assertTrue(js.contains("function serializeSvg(svg)"))
        assertTrue(js.contains("XMLSerializer"))
        assertTrue(js.contains("return null"))
    }

    @Test
    fun `figures in range includes caption resolver and svg serializer`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(js.contains("function resolveCaption(el)"))
        assertTrue(js.contains("function serializeSvg(svg)"))
        assertTrue(js.contains("function figuresInRange(startNode, endNode)"))
    }

    @Test
    fun `figures in range filters on img svg picture figure`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        listOf("'img'", "'svg'", "'picture'", "'figure'").forEach {
            assertTrue("missing filter for $it", js.contains(it))
        }
    }

    @Test
    fun `figures in range picks inner img for picture`() {
        assertTrue(FigureCaptionWalker.FIGURES_IN_RANGE_JS.contains("querySelector('img')"))
    }

    @Test
    fun `figures in range resolves figure to its single img svg or picture child`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(
            js.contains(
                "node.querySelector('img') || node.querySelector('svg') || node.querySelector('picture')"
            )
        )
    }

    @Test
    fun `svg branch serializes and stores under svg key with null href`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(js.contains("serializeSvg"))
        assertTrue(js.contains("entry.svg = serializeSvg(target)"))
        assertTrue(js.contains("entry.href = null"))
    }

    @Test
    fun `figures in range dedupes via a seen set and assigns incrementing order`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue("missing dedupe guard", js.contains("seen.has(target)"))
        assertTrue("missing seen.add", js.contains("seen.add(target)"))
        assertTrue("missing incrementing order", js.contains("order: order++"))
    }

    @Test
    fun `figures in range uses TreeWalker over the start-end range`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(js.contains("document.createRange()"))
        assertTrue(js.contains("range.setStartBefore(startNode)"))
        assertTrue(js.contains("range.setEndAfter(endNode)"))
        assertTrue(js.contains("document.createTreeWalker"))
        assertTrue(js.contains("range.intersectsNode(n)"))
    }

    @Test
    fun `constants are safe to concatenate — no script tags, no leading semicolons`() {
        listOf(
            FigureCaptionWalker.CAPTION_RESOLVER_JS,
            FigureCaptionWalker.SVG_SERIALIZER_JS,
            FigureCaptionWalker.FIGURES_IN_RANGE_JS,
        ).forEach { js ->
            assertFalse("must not contain <script> tags", js.contains("<script", ignoreCase = true))
            assertFalse("must not start with a stray semicolon", js.trim().startsWith(";"))
        }
    }

    @Test
    fun `figures in range JS embeds the resolver and serializer verbatim`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(
            "resolveCaption body not embedded verbatim",
            js.contains(FigureCaptionWalker.CAPTION_RESOLVER_JS),
        )
        assertTrue(
            "serializeSvg body not embedded verbatim",
            js.contains(FigureCaptionWalker.SVG_SERIALIZER_JS),
        )
    }
}
