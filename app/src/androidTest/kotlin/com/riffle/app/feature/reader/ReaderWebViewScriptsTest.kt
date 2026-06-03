package com.riffle.app.feature.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of the two scripts EpubReaderScreen injects into every reflowable page,
 * run against a **real** WebView. The harness AVD is Android 7.1.1 (API 25), whose pre-Chromium-61
 * WebView is exactly the engine both fixes target — so these tests reproduce the original bugs and
 * prove the fix in the environment that actually broke.
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
    // s5 wraps an inner element with no id of its own to exercise the walk-up-to-nearest-id path.
    private val sentenceFixture = """
        <!DOCTYPE html>
        <html>
          <body>
            <p><span id="c007-s2">The quick brown fox.</span> <span id="c007-s5"><em>Jumps over it.</em></span></p>
            <p id="no-sentence-id">Bare paragraph with no sentence span.</p>
          </body>
        </html>
    """.trimIndent()

    // ---- RECT_TO_JSON_POLYFILL_JS ----

    @Test
    fun polyfillMakesGetBoundingClientRectToJsonReturnTheRect() {
        withFixture(rectFixture) { webView ->
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
        withFixture(rectFixture) { webView ->
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
        withFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
        }
    }

    @Test
    fun selectionWalksUpToNearestAncestorWithAnId() {
        withFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // Selection lands inside the <em> (no id) → tracker must walk up to the enclosing span.
            webView.selectWordIn("c007-s5")
            assertEquals("c007-s5", webView.awaitSelSpan())
        }
    }

    @Test
    fun collapsedSelectionDoesNotUpdateTheStashedId() {
        withFixture(sentenceFixture) { webView ->
            webView.evalSync(SELECTION_SPAN_TRACKER_JS)
            // First a real selection so __riffleSelSpan is set...
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
            // ...then collapse it; the tracker ignores collapsed selections, so the value must hold.
            webView.evalSync("window.getSelection().collapseToEnd()")
            // Give any (wrongly fired) update a chance to land, then re-read.
            Thread.sleep(150)
            assertEquals("collapsed selection must not overwrite the stashed span", "c007-s2", webView.evalSync("window.__riffleSelSpan").trim('"'))
        }
    }

    @Test
    fun installIsIdempotent() {
        withFixture(sentenceFixture) { webView ->
            repeat(3) { webView.evalSync(SELECTION_SPAN_TRACKER_JS) }
            assertEquals("true", webView.evalSync("String(window.__riffleSelTrackerInstalled)").trim('"'))
            webView.selectWordIn("c007-s2")
            assertEquals("c007-s2", webView.awaitSelSpan())
        }
    }

    // ---- helpers ----

    // Selects a word inside the text node of [spanId] (or its first descendant text node), so the
    // selection's startContainer is a text node — the realistic shape the tracker must handle.
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

    // selectionchange is dispatched asynchronously; poll until the tracker has stashed a value.
    private fun WebView.awaitSelSpan(timeoutMs: Long = 3_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = evalSync("window.__riffleSelSpan").trim('"')
            if (value.isNotEmpty() && value != "null" && value != "undefined") return value
            Thread.sleep(30)
        }
        return evalSync("window.__riffleSelSpan").trim('"')
    }

    private fun withFixture(html: String, block: (WebView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = CountDownLatch(1)
        val webViewHolder = arrayOfNulls<WebView>(1)
        instrumentation.runOnMainSync {
            val webView = WebView(context).also {
                it.settings.javaScriptEnabled = true
                it.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        ready.countDown()
                    }
                }
            }
            webViewHolder[0] = webView
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        assertTrue("Fixture page did not finish loading", ready.await(10, TimeUnit.SECONDS))
        val webView = webViewHolder[0]!!
        try {
            block(webView)
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun WebView.evalSync(script: String): String {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            evaluateJavascript(script) { value ->
                result[0] = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out for: $script", latch.await(5, TimeUnit.SECONDS))
        val value = result[0]
        assertNotNull("evaluateJavascript returned null for: $script", value)
        return value!!
    }
}
