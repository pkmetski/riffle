package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of the two scripts EpubReaderScreen injects into every reflowable page,
 * run against a **real** WebView via [withWebViewFixture]. The harness AVD is Android 7.1.1 (API 25),
 * whose pre-Chromium-61 WebView is exactly the engine both fixes target — so these tests reproduce
 * the original bugs and prove the fix in the environment that actually broke.
 *
 * Covers:
 *  - [RECT_TO_JSON_POLYFILL_JS]: on the old engine `getBoundingClientRect()` returns a ClientRect
 *    with no `toJSON()`, which made Readium's tap handler throw before delivering taps (the user
 *    couldn't toggle immersive mode during readaloud). The polyfill restores a working `toJSON()`.
 *  - [SELECTION_SPAN_TRACKER_JS]: stashes the narrated-sentence span id under the selection in
 *    `window.__riffleSelSpan`, which "Play from here" reads to start at the selected sentence rather
 *    than restarting the chapter.
 */
@RunWith(AndroidJUnit4::class)
class ReaderWebViewScriptsTest {

    private val rectFixture = """
        <!DOCTYPE html>
        <html>
          <head><style>#target { position: absolute; left: 10px; top: 20px; width: 100px; height: 30px; }</style></head>
          <body><div id="target">box</div></body>
        </html>
    """.trimIndent()

    // Sentence spans mirror the Storyteller bundle shape: each narrated sentence is a <span id="cNNN-sM">.
    // s5 wraps an inner element with no id of its own to exercise the walk-up-to-nearest-id path; the
    // last paragraph has no id anywhere in its ancestry to exercise the "no sentence span" fallback.
    private val sentenceFixture = """
        <!DOCTYPE html>
        <html>
          <body>
            <p><span id="c007-s2">The quick brown fox.</span> <span id="c007-s5"><em>Jumps over it.</em></span></p>
            <p>Bare paragraph with no id in its ancestry.</p>
          </body>
        </html>
    """.trimIndent()

    // ---- RECT_TO_JSON_POLYFILL_JS ----

    @Test
    fun polyfillMakesGetBoundingClientRectToJsonReturnTheRect() {
        withWebViewFixture(rectFixture) { webView ->
            webView.evalSync(RECT_TO_JSON_POLYFILL_JS)
            // This is the exact call Readium's reflowable tap handler makes; on the un-polyfilled
            // API-25 engine it throws "toJSON is not a function" and swallows the tap.
            val typeOfToJson = webView.evalSync(
                "typeof document.getElementById('target').getBoundingClientRect().toJSON"
            ).trim('"')
            assertEquals("polyfill must install a callable toJSON()", "function", typeOfToJson)

            val json = webView.evalSync(
                "JSON.stringify(document.getElementById('target').getBoundingClientRect().toJSON())"
            )
            // evaluateJavascript wraps strings in quotes and escapes inner quotes.
            val obj = JSONObject(json.trim('"').replace("\\\"", "\""))
            assertEquals("toJSON must carry the rect width", 100.0, obj.getDouble("width"), 0.5)
            assertEquals("toJSON must carry the rect height", 30.0, obj.getDouble("height"), 0.5)
            assertEquals("toJSON must carry the rect left", 10.0, obj.getDouble("left"), 0.5)
            assertEquals("toJSON must carry the rect top", 20.0, obj.getDouble("top"), 0.5)
        }
    }

    @Test
    fun polyfillIsIdempotentAndKeepsToJsonWorking() {
        withWebViewFixture(rectFixture) { webView ->
            repeat(3) { webView.evalSync(RECT_TO_JSON_POLYFILL_JS) }
            val typeOfToJson = webView.evalSync(
                "typeof document.getElementById('target').getBoundingClientRect().toJSON"
            ).trim('"')
            assertEquals("repeat injection must leave a working toJSON()", "function", typeOfToJson)
        }
    }

    // ---- SELECTION_SPAN_TRACKER_JS ----

    @Test
    fun selectionInsideSentenceSpanStashesItsId() {
        withWebViewFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
        }
    }

    @Test
    fun selectionWalksUpToNearestAncestorWithAnId() {
        withWebViewFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // Selection lands inside the <em> (no id) → tracker must walk up to the enclosing span.
            webView.selectWordIn("c007-s5")
            assertEquals("c007-s5", webView.awaitSelSpan())
        }
    }

    @Test
    fun selectionWithoutASentenceSpanResetsTheStashedIdRatherThanLeavingItStale() {
        withWebViewFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // Set a real span id first...
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
            // ...then select text whose ancestry has no id. The tracker must clear the stash to "" so
            // "Play from here" falls back to the chapter position, NOT the previously-selected sentence.
            webView.selectFirstWordOfBareParagraph()
            assertEquals("", webView.awaitSelSpan(expectEmpty = true))
        }
    }

    @Test
    fun collapsedSelectionDoesNotUpdateTheStashedId() {
        withWebViewFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
            // Collapse it; the tracker ignores collapsed selections, so the value must hold.
            webView.evalSync("window.getSelection().collapseToEnd()")
            Thread.sleep(150)
            assertEquals(
                "collapsed selection must not overwrite the stashed span",
                "c007-s2",
                webView.evalSync("window.__riffleSelSpan").trim('"'),
            )
        }
    }

    @Test
    fun installIsIdempotent() {
        withWebViewFixture(sentenceFixture) { webView ->
            repeat(3) { webView.evalSync(SELECTION_SPAN_TRACKER_JS) }
            assertEquals("true", webView.evalSync("String(window.__riffleSelTrackerInstalled)").trim('"'))
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
        }
    }

    // ---- resolveSelectionSentenceJs ----
    //
    // These exercise the GEOMETRY-based resolver against a real, sized WebView: we drive a real text
    // selection (which fires selectionchange so the tracker stashes window.__riffleSelRect), then ask the
    // resolver which narrated sentence the selection landed in. The fixtures mirror the DOM Readium serves
    // for a readaloud (ABS) EPUB — the per-sentence span ids are stripped, leaving only prose — and the
    // [resolveSentences] carry the bundle's flattened sentence text keyed by span id.

    // "cat" recurs across sentences AND paragraphs, so resolving by the bare word would be ambiguous —
    // the resolver must use the selection's POSITION.
    private val strippedFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <p>The cat sat on the mat. The cat ran away quickly.</p>
            <p>Later the cat returned home. The dog barked twice.</p>
          </body>
        </html>
    """.trimIndent()

    private val strippedSentences = listOf(
        "c1-s0" to "The cat sat on the mat.",
        "c1-s1" to "The cat ran away quickly.",
        "c1-s2" to "Later the cat returned home.",
        "c1-s3" to "The dog barked twice.",
    )

    // Mirrors the real Project Hail Mary failure: the served page italicises the recurring ship name
    // ("<em>Hermes</em>"), splitting each such sentence into multiple text nodes, and the bundle's
    // sentence text ("…to Hermes." / "Once we got to Hermes, …") differs from the rendered run. Geometry
    // sidesteps all of that. A selection on the LAST Hermes sentence must resolve to THAT sentence — not
    // the earlier markup-free one (the bug: selected "Once we got to Hermes…", got "I guess I should…").
    private val italicFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <p>I guess I should explain how Mars missions work, for any layman who may be reading this.</p>
            <p>We got to Earth orbit the normal way, through an ordinary ship to <em>Hermes</em>.</p>
            <p>Once we got to <em>Hermes</em>, four additional unmanned missions brought us fuel.</p>
          </body>
        </html>
    """.trimIndent()

    private val italicSentences = listOf(
        "s0" to "I guess I should explain how Mars missions work, for any layman who may be reading this.",
        "s1" to "We got to Earth orbit the normal way, through an ordinary ship to Hermes.",
        "s2" to "Once we got to Hermes, four additional unmanned missions brought us fuel.",
    )

    @Test
    fun resolveSelectionResolvesTheSentenceContainingTheSelection() {
        withSizedWebViewFixture(strippedFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectNthOccurrence("sat", 1) // first sentence
            assertTrue("tracker must stash a selection rect", webView.awaitSelRect())
            assertEquals("c1-s0", webView.evalSync(resolveSelectionSentenceJs(strippedSentences)).trim('"'))
        }
    }

    @Test
    fun resolveSelectionPicksTheRightOccurrenceOfARecurringWord() {
        withSizedWebViewFixture(strippedFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // "cat" occurs in s0, s1, s2 (in reading order). The 3rd lands in s2 — bare word-matching
            // would pick the 1st; geometry picks the sentence the tap is actually in.
            webView.selectNthOccurrence("cat", 3)
            assertTrue(webView.awaitSelRect())
            assertEquals("c1-s2", webView.evalSync(resolveSelectionSentenceJs(strippedSentences)).trim('"'))
        }
    }

    @Test
    fun resolveSelectionPicksTheSecondSentenceForAWordOnlyItContains() {
        withSizedWebViewFixture(strippedFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectNthOccurrence("ran", 1) // only in s1
            assertTrue(webView.awaitSelRect())
            assertEquals("c1-s1", webView.evalSync(resolveSelectionSentenceJs(strippedSentences)).trim('"'))
        }
    }

    @Test
    fun resolveSelectionResolvesASentenceContainingInlineMarkup() {
        withSizedWebViewFixture(italicFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectNthOccurrence("Hermes", 2) // 2nd Hermes is in s2
            assertTrue(webView.awaitSelRect())
            assertEquals(
                "the sentence with the italic ship name must resolve to itself, not the earlier sentence",
                "s2",
                webView.evalSync(resolveSelectionSentenceJs(italicSentences)).trim('"'),
            )
        }
    }

    @Test
    fun resolveSelectionResolvesTheMiddleSentenceWithInlineMarkup() {
        withSizedWebViewFixture(italicFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectNthOccurrence("Hermes", 1) // 1st Hermes is in s1
            assertTrue(webView.awaitSelRect())
            assertEquals("s1", webView.evalSync(resolveSelectionSentenceJs(italicSentences)).trim('"'))
        }
    }

    // A punctuation-only "sentence" — Storyteller emits fragments like `…”` as their own narrated span.
    // Its 1–2 char key matches that SAME punctuation INSIDE a real compound sentence ("…to save lives,
    // I'd…" He thought…"), and, sitting later than the real sentence's start, would wrongly win. The
    // resolver must skip such degenerate keys. Mirrors the real The Martian ch16 within-chapter misfire
    // (a tap inside id34-s833 resolved to the `…”` fragment id34-s213).
    private val degenerateFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <p>“But if I wasn’t willing to take risks to save lives, I’d…” He thought for a moment.</p>
          </body>
        </html>
    """.trimIndent()

    private val degenerateSentences = listOf(
        "s-deg" to "…”",
        "s-real" to "“But if I wasn’t willing to take risks to save lives, I’d…” He thought for a moment.",
    )

    @Test
    fun resolveSelectionSkipsPunctuationOnlySentenceFragments() {
        withSizedWebViewFixture(degenerateFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectNthOccurrence("thought", 1) // inside the real compound sentence, after the `…”`
            assertTrue(webView.awaitSelRect())
            assertEquals(
                "must resolve to the real compound sentence, not the punctuation-only `…”` fragment that matches mid-sentence",
                "s-real",
                webView.evalSync(resolveSelectionSentenceJs(degenerateSentences)).trim('"'),
            )
        }
    }

    @Test
    fun resolveSelectionReturnsEmptyWhenNoSelectionRectIsStashed() {
        withSizedWebViewFixture(strippedFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // No selection has been made, so __riffleSelRect is unset → resolver yields "" (caller falls back).
            assertEquals("", webView.evalSync(resolveSelectionSentenceJs(strippedSentences)).trim('"'))
        }
    }

    // ---- Continuous mode (tall document) ----
    //
    // In ContinuousReaderView each ChapterWebView is auto-sized to its content height, so
    // window.innerHeight equals the chapter's full rendered height — potentially much larger than
    // the visible viewport. Every sentence therefore passes the on-screen filter
    // (r.top < window.innerHeight) and all are candidates. The resolver must still pick the sentence
    // at the SELECTION POSITION, not the first occurrence in reading order.
    //
    // These tests verify that heightPx >> content height (the continuous mode shape) does not
    // break position-based disambiguation. Reuses strippedFixture (its recurring "cat" word) with a
    // 3000 device-px WebView — far taller than the few-line content, just as a chapter WebView is
    // far taller than a typical screen in continuous mode.

    @Test
    fun resolveSelectionPicksCorrectSentenceInContinuousModeWhereAllSentencesPassTheOnScreenFilter() {
        withSizedWebViewFixture(strippedFixture, widthPx = 1080, heightPx = 3000) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // "cat" occurs in s0, s1, s2. Text matching would always return s0 (first occurrence).
            // With a 3000-device-px-tall WebView (all sentences well within window.innerHeight),
            // geometry must still resolve to the sentence at the actual selection position.
            webView.selectNthOccurrence("cat", 3)
            assertTrue("tracker must stash a rect in a tall continuous-mode WebView", webView.awaitSelRect())
            assertEquals(
                "geometry resolves to the selected sentence when window.innerHeight exceeds content height",
                "c1-s2",
                webView.evalSync(resolveSelectionSentenceJs(strippedSentences)).trim('"'),
            )
        }
    }

    // ---- helpers ----

    // Selects the [n]-th occurrence (1-based, document order) of [word], driving a real selection so the
    // tracker's selectionchange listener fires and stashes window.__riffleSelRect — the realistic path.
    private fun WebView.selectNthOccurrence(word: String, n: Int) {
        evalSync(
            """
            (function () {
              var word = ${org.json.JSONObject.quote(word)}, n = $n, count = 0;
              var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), node;
              while (node = w.nextNode()) {
                var from = 0, i;
                while ((i = node.nodeValue.indexOf(word, from)) >= 0) {
                  count++;
                  if (count === n) {
                    var range = document.createRange();
                    range.setStart(node, i);
                    range.setEnd(node, i + word.length);
                    var sel = window.getSelection();
                    sel.removeAllRanges();
                    sel.addRange(range);
                    return 'ok';
                  }
                  from = i + word.length;
                }
              }
              return 'notfound';
            })();
            """.trimIndent(),
        )
    }

    // Polls until the tracker has stashed a selection rect (selectionchange is dispatched asynchronously).
    private fun WebView.awaitSelRect(timeoutMs: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (evalSync("window.__riffleSelRect ? 'y' : 'n'").trim('"') == "y") return true
            Thread.sleep(30)
        }
        return false
    }

    // Selects a word inside the text node of [spanId], so the selection's startContainer is a text
    // node — the realistic shape the tracker must handle.
    private fun WebView.selectWordIn(spanId: String) {
        evalSync(
            """
            (function () {
              var el = document.getElementById('$spanId');
              var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
              var textNode = walker.nextNode();
              var range = document.createRange();
              range.setStart(textNode, 0);
              range.setEnd(textNode, Math.min(3, textNode.length));
              var sel = window.getSelection();
              sel.removeAllRanges();
              sel.addRange(range);
              return 'ok';
            })();
            """.trimIndent(),
        )
    }

    // Selects inside the fixture's last <p>, which has no id anywhere in its ancestry.
    private fun WebView.selectFirstWordOfBareParagraph() {
        evalSync(
            """
            (function () {
              var ps = document.getElementsByTagName('p');
              var p = ps[ps.length - 1];
              var textNode = p.firstChild;
              var range = document.createRange();
              range.setStart(textNode, 0);
              range.setEnd(textNode, 4);
              var sel = window.getSelection();
              sel.removeAllRanges();
              sel.addRange(range);
              return 'ok';
            })();
            """.trimIndent(),
        )
    }

    // selectionchange is dispatched asynchronously; poll until the tracker has written a value. When
    // [expectEmpty], we instead wait for the stash to settle to "" (the reset case).
    private fun WebView.awaitSelSpan(timeoutMs: Long = 3_000, expectEmpty: Boolean = false): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = evalSync("window.__riffleSelSpan").trim('"')
            val settled = if (expectEmpty) value == "" else value.isNotEmpty() && value != "null" && value != "undefined"
            if (settled) return value
            Thread.sleep(30)
        }
        return evalSync("window.__riffleSelSpan").trim('"')
    }
}
