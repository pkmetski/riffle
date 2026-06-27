package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnose continuous-mode annotation rendering: load a tiny EPUB-like body in a real WebView,
 * run [ContinuousStyleInjector.applyAnnotationHighlightsJs] against it, and inspect the resulting
 * DOM for the expected `<mark data-riffle-ann="...">` element.
 */
class ContinuousAnnotationHighlightsJsTest {

    private val html = """
        <!doctype html>
        <html><head><meta charset="utf-8"></head>
        <body>
          <p id="p1">The quick brown fox jumps over the lazy dog.</p>
          <p id="p2">Another paragraph with some more text in it for context.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun applies_mark_to_simple_snippet() {
        withWebViewFixture(html) { wv ->
            val ann = AnnotationHighlight(
                id = "ann-1",
                text = "quick brown fox",
                cssColor = "rgba(255,235,59,0.45)",
                hasNote = false,
                before = "The ",
                after = " jumps",
            )
            val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(ann))
            wv.evalSync(js)
            val count = wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"ann-1\"]').length")
            assertEquals("1", count.trim('"'))
            val text = wv.evalSync("document.querySelector('[data-riffle-ann=\"ann-1\"]').textContent")
            assertEquals("\"quick brown fox\"", text)
        }
    }

    @Test
    fun applies_mark_with_empty_before_after() {
        withWebViewFixture(html) { wv ->
            val ann = AnnotationHighlight(
                id = "ann-2",
                text = "Another paragraph",
                cssColor = "rgba(76,175,80,0.45)",
                hasNote = false,
                before = "",
                after = "",
            )
            val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(ann))
            wv.evalSync(js)
            val count = wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"ann-2\"]').length")
            assertEquals("1", count.trim('"'))
        }
    }

    @Test
    fun emits_note_glyph_when_hasNote() {
        withWebViewFixture(html) { wv ->
            val ann = AnnotationHighlight(
                id = "ann-3",
                text = "lazy dog",
                cssColor = "rgba(33,150,243,0.45)",
                hasNote = true,
                before = "the ",
                after = ".",
            )
            val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(ann))
            wv.evalSync(js)
            val glyph = wv.evalSync("document.querySelectorAll('[data-riffle-note-glyph=\"ann-3\"]').length")
            assertEquals("1", glyph.trim('"'))
        }
    }

    @Test
    fun multiple_annotations_in_different_paragraphs_apply() {
        withWebViewFixture(html) { wv ->
            val a1 = AnnotationHighlight("a1", "quick brown fox", "rgba(255,235,59,0.45)", false, "The ", " jumps")
            val a2 = AnnotationHighlight("a2", "Another paragraph", "rgba(76,175,80,0.45)", false, "", " with")
            val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(a1, a2))
            wv.evalSync(js)
            val c1 = wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"a1\"]').length").trim('"')
            val c2 = wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"a2\"]').length").trim('"')
            assertTrue("a1 present, got $c1", c1 == "1")
            assertTrue("a2 present, got $c2", c2 == "1")
        }
    }
}
