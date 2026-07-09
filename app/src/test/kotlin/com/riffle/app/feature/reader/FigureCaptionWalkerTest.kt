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
