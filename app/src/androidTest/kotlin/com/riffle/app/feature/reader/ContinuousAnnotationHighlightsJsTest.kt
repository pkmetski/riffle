package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.riffle.feature.reader.AnnotationHighlight

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

    // Regression: when an annotation mark for a different word sits between two occurrences of the
    // target word, the bef context (computed by SELECTION_SPAN_TRACKER_JS) must exclude the annotated
    // word's text — matching flatIdx.fullText's representation — so findIdx can disambiguate the
    // right occurrence. Before the fix, bef included the annotated word (via Range.toString()),
    // causing beforeWin ≠ bef for both occurrences and findIdx falling back to firstIdx (wrong word).
    @Test
    fun disambiguates_second_occurrence_when_another_annotation_sits_between_them() {
        val twoFoxHtml = """
            <!doctype html>
            <html><head><meta charset="utf-8"></head>
            <body>
              <p id="p1">fox dog fox</p>
            </body></html>
        """.trimIndent()
        withWebViewFixture(twoFoxHtml) { wv ->
            // Apply an existing annotation on "dog" (between the two "fox" occurrences).
            val dogAnn = AnnotationHighlight("ann-dog", "dog", "rgba(255,235,59,0.45)", false, "fox ", " fox")
            wv.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(dogAnn)))
            assertEquals(
                "pre-condition: 'dog' must be annotated",
                "1",
                wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"ann-dog\"]').length").trim('"'),
            )
            // The second "fox" was selected after "dog" was annotated, so bef is computed by the
            // updated SELECTION_SPAN_TRACKER_JS which walks text nodes while skipping [data-riffle-ann]
            // marks — identical to buildFlat. Result: before="fox  " (double-space where "dog" was,
            // 4 chars from "fox " + 1 space-prefix from " fox"). findIdx("fox","fox  ","",0) in
            // flatIdx.fullText="fox  fox" matches at offset 5 (second "fox"), not offset 0 (first).
            val secondFoxAnn = AnnotationHighlight("ann-fox", "fox", "rgba(76,175,80,0.45)", false, "fox  ", "")
            wv.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(dogAnn, secondFoxAnn)))
            assertEquals(
                "second 'fox' must be annotated",
                "1",
                wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"ann-fox\"]').length").trim('"'),
            )
            // ann-fox must follow ann-dog in document order (it is the SECOND "fox").
            val order = wv.evalSync(
                "(function(){var ms=document.querySelectorAll('[data-riffle-ann]');" +
                    "return Array.prototype.map.call(ms,function(m){return m.getAttribute('data-riffle-ann');}).join(',');})()",
            ).trim('"')
            assertEquals("ann-dog,ann-fox", order)
        }
    }

    // Regression: when the draft mark ("__draft__") is in the DOM while the committed annotation is
    // being located (a race where Room emits before consumeDraft removes the draft mark), buildFlat
    // must NOT skip the draft mark's text. Before the fix, buildFlat skipped all [data-riffle-ann]
    // marks including the draft, so the target word was absent from flatIdx.fullText and findIdx fell
    // back to the first occurrence (wrong word). After the fix, the committed mark is nested inside
    // the draft mark; the next applyAnnotationHighlights call (after consumeDraft cleans up the
    // draft) promotes the committed mark via outerHTML = innerHTML.
    @Test
    fun committed_annotation_placed_on_correct_word_while_draft_mark_is_in_dom() {
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"></head>
            <body>
              <p id="p1">word word</p>
            </body></html>
        """.trimIndent()
        withWebViewFixture(html) { wv ->
            // Simulate the draft mark being in the DOM (placed by the prior applyAnnotationHighlights
            // call that rendered the draft). The user selected the SECOND "word".
            wv.evalSync(
                "(function(){" +
                    "var p=document.getElementById('p1');" +
                    "var t=p.firstChild;" + // text node "word word"
                    "var r=document.createRange();" +
                    "r.setStart(t,5);r.setEnd(t,9);" + // "word" at offset 5..9 (second "word")
                    "var m=document.createElement('mark');" +
                    "m.setAttribute('data-riffle-ann','__draft__');" +
                    "r.surroundContents(m);" +
                "})()",
            )
            assertEquals(
                "pre-condition: draft mark wraps second 'word'",
                "1",
                wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"__draft__\"]').length").trim('"'),
            )
            // The committed annotation targets the second "word". bef = "word " (first "word" + space).
            // buildFlat must include the draft mark text so findIdx locates the word at the right index.
            val committedAnn = AnnotationHighlight("ann-committed", "word", "rgba(76,175,80,0.45)", false, "word ", "")
            wv.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(committedAnn)))
            // Now simulate consumeDraft cleanup: applyAnnotationHighlightsJs without the draft in
            // the valid-id set removes the draft mark (outerHTML = innerHTML), unveiling the nested
            // committed mark. Call again without the draft in the list to trigger cleanup.
            // (In production this happens on the next applyAnnotationHighlights after consumeDraft.)
            wv.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(committedAnn)))
            assertEquals(
                "committed mark must be present after draft cleanup",
                "1",
                wv.evalSync("document.querySelectorAll('[data-riffle-ann=\"ann-committed\"]').length").trim('"'),
            )
            // The committed mark must be on the SECOND "word" — it must follow the first "word" text.
            val firstWordText = wv.evalSync(
                "(function(){" +
                    "var p=document.getElementById('p1');" +
                    "var mark=p.querySelector('[data-riffle-ann=\"ann-committed\"]');" +
                    "var prev=mark.previousSibling;" +
                    "return prev ? prev.nodeValue || '' : '';" +
                "})()",
            ).trim('"')
            assertEquals("word ", firstWordText)
        }
    }
}
