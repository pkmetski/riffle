package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-WebView regression coverage for Android-selection-style highlight line bands. */
@RunWith(AndroidJUnit4::class)
class HighlightLineCoverageWebViewTest {

    private data class Rect(val top: Double, val bottom: Double)

    private val fixture = """
        <!doctype html>
        <html>
          <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <p id="target" style="width:280px;margin:40px 0 0 20px;font-family:serif;font-size:32px;line-height:48px">
              Medication is probably the most widely publicized, most hotly debated treatment for ADHD.
            </p>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun continuousAnnotationMarkCoversFullLineBandsWithoutGaps() {
        withSizedWebViewFixture(fixture, widthPx = 480, heightPx = 800) { webView ->
            webView.awaitInnerHeight()
            val annotation = AnnotationHighlight(
                id = "ann",
                text = "Medication is probably the most widely publicized, most hotly debated treatment for ADHD.",
                cssColor = "rgba(251,191,36,0.50)",
                hasNote = false,
            )
            webView.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(annotation)))

            assertNativeSelectionLineCoverage(
                painted = webView.rects("document.querySelector('[data-riffle-ann=\"ann\"]')"),
                text = webView.rangeRects("document.querySelector('[data-riffle-ann=\"ann\"]')"),
            )
        }
    }

    @Test
    fun readiumDecorationBoxesCoverFullLineBandsWithoutGaps() {
        withSizedWebViewFixture(fixture, widthPx = 480, heightPx = 800) { webView ->
            webView.awaitInnerHeight()
            val stylesheet = highlightTintTemplate().stylesheet.orEmpty()
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("$", "\\$")
            webView.evalSync(
                """
                (function() {
                  var style = document.createElement('style');
                  style.textContent = `$stylesheet`;
                  document.head.appendChild(style);
                  var range = document.createRange();
                  range.selectNodeContents(document.getElementById('target'));
                  window.__riffleRawHighlightRects = Array.prototype.map.call(
                    range.getClientRects(),
                    function(r) { return {top:r.top,bottom:r.bottom}; }
                  );
                  Array.prototype.forEach.call(range.getClientRects(), function(r) {
                    var box = document.createElement('div');
                    box.className = 'riffle-highlight-tint';
                    box.style.cssText = 'position:absolute;pointer-events:none;' +
                      'left:' + (r.left + window.pageXOffset) + 'px;' +
                      'top:' + (r.top + window.pageYOffset) + 'px;' +
                      'width:' + r.width + 'px;height:' + r.height + 'px;' +
                      'background:rgba(251,191,36,0.50) !important;';
                    document.body.appendChild(box);
                  });
                })();
                """.trimIndent(),
            )
            webView.evalSync(readiumHighlightLeadingAdjustmentJs())
            webView.awaitReadiumLeadingAdjustment()

            assertNativeSelectionLineCoverage(
                painted = webView.rects("document.querySelectorAll('.riffle-highlight-tint')"),
                text = webView.savedRawRects(),
            )
        }
    }

    private fun assertNativeSelectionLineCoverage(painted: List<Rect>, text: List<Rect>) {
        assertTrue("fixture must wrap to at least three lines; got $painted", painted.size >= 3)
        val largestGap = painted.zipWithNext { current, next -> next.top - current.bottom }.max()
        assertTrue("highlight lines must meet without white gaps; largest gap=$largestGap, rects=$painted", largestGap <= 0.75)
        val topCoverage = text.first().top - painted.first().top
        assertTrue("highlight must cover the line-leading above the glyph box; coverage=$topCoverage", topCoverage >= 2.0)
    }

    private fun android.webkit.WebView.rects(selector: String): List<Rect> =
        parseRects(
            evalSync(
                """
                (function() {
                  var target = $selector;
                  var list = target && typeof target.length === 'number' ? target : [target];
                  var out = [];
                  Array.prototype.forEach.call(list, function(el) {
                    if (!el) return;
                    Array.prototype.forEach.call(el.getClientRects(), function(r) {
                      if (r.width > 0 && r.height > 0) out.push({top:r.top,bottom:r.bottom});
                    });
                  });
                  out.sort(function(a,b) { return a.top - b.top; });
                  return out;
                })();
                """.trimIndent(),
            ),
        )

    private fun android.webkit.WebView.rangeRects(element: String): List<Rect> =
        parseRects(
            evalSync(
                """
                (function() {
                  var range = document.createRange();
                  range.selectNodeContents($element);
                  return Array.prototype.map.call(range.getClientRects(), function(r) {
                    return {top:r.top,bottom:r.bottom};
                  });
                })();
                """.trimIndent(),
            ),
        )

    private fun android.webkit.WebView.savedRawRects(): List<Rect> =
        parseRects(evalSync("window.__riffleRawHighlightRects"))

    private fun android.webkit.WebView.awaitReadiumLeadingAdjustment() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (evalSync("document.querySelectorAll('[data-riffle-base-height]').length").trim('"') != "0") return
            Thread.sleep(20)
        }
        throw AssertionError("Readium highlight leading adjustment did not run")
    }

    private fun parseRects(raw: String): List<Rect> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Rect(top = item.getDouble("top"), bottom = item.getDouble("bottom"))
        }
    }
}
