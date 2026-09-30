package com.riffle.feature.reader

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

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
        assertTrue(figIdx in 0 until altIdx, "figcaption lookup missing or out of order")
        assertTrue(altIdx in 0 until ariaIdx, "alt lookup missing or out of order")
    }

    @Test
    fun `caption resolver checks h6 inside figure before falling back to alt attribute`() {
        // O'Reilly EPUBs place the real caption in an <h6> inside the <figure> wrapper, and also
        // put accessibility alt-descriptions in the <img alt="…"> attribute. Without this check,
        // resolveCaption falls through to `alt` and returns "A pink chart … Description
        // automatically generated" instead of "Figure 5-4. The effect of…".
        //
        // The h6/h5/h4/h3 scan is gated on CAPTION_PREFIX_RX so accessibility alt-descriptions
        // placed in <h6> (which do NOT start with "Figure N") are excluded.
        //
        // Reverting to figcaption-only inside resolveFigcaptionElement — or moving the h6 scan
        // after the alt fallback — flips this red.
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        val resolveFnEnd = js.indexOf("function resolveTextPrefixElement(el)")
        val resolveFn = js.substring(0, resolveFnEnd)
        assertTrue(resolveFn.contains("'h6'"), "resolveFigcaptionElement must scan h6 (O'Reilly caption style)")
        assertTrue(resolveFn.contains("'h5'"), "resolveFigcaptionElement must scan h5")
        assertTrue(
            resolveFn.contains("CAPTION_PREFIX_RX.test"),
            "h6 scan inside resolveFigcaptionElement must be gated on CAPTION_PREFIX_RX",
        )
        val h6ScanIdx = js.indexOf("'h6'")
        val altIdx = js.indexOf("'alt'")
        assertTrue(
            h6ScanIdx in 0 until altIdx,
            "h6 scan inside figure must precede the alt-attribute fallback in resolveCaption",
        )
    }

    @Test
    fun `text-prefix fallback never borrows a caption from a different figure`() {
        // O'Reilly ch09: an uncaptioned (or h6-captioned, pre-#1125) Figure 9-1's 3-hop walk
        // reaches the section, where Figure 9-2's `<div class="figure">` starts with "Figure 9-2"
        // — and was returned as 9-1's caption, so the long-press stored 9-2's caption + 9-1's
        // image. Candidates inside a different figure wrapper must be skipped.
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        val start = js.indexOf("function resolveTextPrefixElement(el)")
        val end = js.indexOf("function resolveCaption(el)")
        assertTrue(start in 0 until end, "resolveTextPrefixElement/resolveCaption not found in order")
        val body = js.substring(start, end)
        assertTrue(
            body.contains("el.closest('figure, [role=\"figure\"]')"),
            "must resolve the long-pressed element's own figure wrapper",
        )
        assertTrue(
            body.contains("b.closest('figure, [role=\"figure\"]')"),
            "must resolve each candidate block's figure wrapper",
        )
        assertTrue(
            body.contains("if (bFig && bFig !== ownFig) continue;"),
            "must skip candidates whose figure wrapper differs from the element's own",
        )
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
            js.contains("(Figure|Fig\\.?|Table|Chart)"),
            "caption resolver should carry the caption-prefix regex",
        )
        assertTrue(
            js.contains("compareDocumentPosition"),
            "caption resolver should walk parent chain (compareDocumentPosition)",
        )
        // The fallback must sit AFTER the alt/aria-label paths in resolveCaption so a legitimate
        // per-image alt attribute always wins over a proximity-based heuristic that could match
        // nearby prose like "Table 3 summarizes results...".
        val ariaIdx = js.indexOf("if (aria) return aria;")
        assertTrue(ariaIdx > 0, "resolveCaption must handle aria before falling through")
        val prefixCallIdx = js.indexOf("resolveTextPrefixElement(el)", ariaIdx)
        assertTrue(
            prefixCallIdx > ariaIdx,
            "text-prefix fallback must come after aria-label inside resolveCaption",
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
            rangeBody.contains("resolveFigcaptionElement(el)"),
            "resolveCaptionRange must call resolveFigcaptionElement",
        )
        assertTrue(
            rangeBody.contains("resolveTextPrefixElement(el)"),
            "resolveCaptionRange must call resolveTextPrefixElement",
        )
        // Slice at the next function boundary — resolveCaptionRange's body must not read alt or
        // aria-label. `next` is the START of `function riffleCollectTextAround` above OR
        // undefined if resolveCaptionRange is the last function; either way the slice below is
        // safe (substring from rangeStart to end of file if no boundary).
        val nextBoundary = rangeBody.indexOf("function ", startIndex = 40)
        val bodyOnly = if (nextBoundary >= 0) rangeBody.substring(0, nextBoundary) else rangeBody
        assertFalse(
            bodyOnly.contains("'alt'"),
            "resolveCaptionRange body must not consult alt",
        )
        assertFalse(
            bodyOnly.contains("'aria-label'"),
            "resolveCaptionRange body must not consult aria-label",
        )
    }

    @Test
    fun `caption resolver falls back to empty string`() {
        val js = FigureCaptionWalker.CAPTION_RESOLVER_JS
        assertTrue(js.contains("function resolveCaption(el)"))
        assertTrue(js.contains("return \"\";"), "missing final empty-string fallback")
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
            assertTrue(js.contains(it), "missing filter for $it")
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
        assertTrue(js.contains("seen.has(target)"), "missing dedupe guard")
        assertTrue(js.contains("seen.add(target)"), "missing seen.add")
        assertTrue(js.contains("order: order++"), "missing incrementing order")
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
    fun constantsAreSafeToConcatenateNoScriptTagsNoLeadingSemicolons() {
        listOf(
            FigureCaptionWalker.CAPTION_RESOLVER_JS,
            FigureCaptionWalker.SVG_SERIALIZER_JS,
            FigureCaptionWalker.FIGURES_IN_RANGE_JS,
        ).forEach { js ->
            assertFalse(js.contains("<script", ignoreCase = true), "must not contain <script> tags")
            assertFalse(js.trim().startsWith(";"), "must not start with a stray semicolon")
        }
    }

    @Test
    fun `figures in range JS embeds the resolver and serializer verbatim`() {
        val js = FigureCaptionWalker.FIGURES_IN_RANGE_JS
        assertTrue(
            js.contains(FigureCaptionWalker.CAPTION_RESOLVER_JS),
            "resolveCaption body not embedded verbatim",
        )
        assertTrue(
            js.contains(FigureCaptionWalker.SVG_SERIALIZER_JS),
            "serializeSvg body not embedded verbatim",
        )
    }
}
