package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the early-exit branches of [ColumnSnap.measureNarratedColumnsJs] against a real WebView:
 * `"off"` when the target text isn't on the page, `"scroll"` when the document is in vertical
 * scroll mode rather than paginated, and `[1.0]` when the sentence occupies a single column.
 *
 * Multi-column geometry (a spanning sentence's per-column fractions and the snap math that moves
 * the page across the grid) used to be covered here as well — those tests depended on the
 * frozen-2017 Chrome 55 WebView in API 25's system image laying out CSS multicol in a specific
 * way that varies across CPU architectures, which made the assertions environment-flaky without
 * representing real users (production WebView is auto-updated to current Chromium via Play
 * Store). The decision half of the sentence-spanning-page follow is exercised by
 * `NarratedColumnProgressionTest` in JVM unit tests, and the Kotlin-side parsing of the JS
 * result by `NarratedColumnsResultParserTest`.
 */
@RunWith(AndroidJUnit4::class)
class NarratedColumnsJsTest {

    private val singleColText = "Short single column line"

    private val singleColumnFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <div style="height:100vh; columns:100vw; column-gap:0; column-fill:auto; font-size:18px; line-height:26px">
              <span>$singleColText</span>
            </div>
          </body>
        </html>
    """.trimIndent()

    // A document much taller than the viewport → scroll (vertical) mode, where intra-sentence column
    // following does not apply.
    private val tallFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div style="position:absolute; top:3000px">$singleColText</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun measuresASingleLineSentenceAsOneColumn() {
        withSizedWebViewFixture(singleColumnFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val raw = webView.evalSync(ColumnSnap.measureNarratedColumnsJs(singleColText)).trim('"')
            val arr = JSONArray(raw)
            assertEquals("a one-line sentence occupies a single column (got $raw)", 1, arr.length())
            assertEquals(1.0, arr.getDouble(0), 0.0001)
        }
    }

    @Test
    fun returnsScrollForAVerticallyOverflowingDocument() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("scroll", webView.evalSync(ColumnSnap.measureNarratedColumnsJs(singleColText)).trim('"'))
        }
    }

    @Test
    fun returnsOffForTextNotOnThePage() {
        withSizedWebViewFixture(singleColumnFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("off", webView.evalSync(ColumnSnap.measureNarratedColumnsJs("Zzz nonexistent text")).trim('"'))
            assertEquals("off", webView.evalSync(ColumnSnap.measureNarratedColumnsJs("")).trim('"'))
        }
    }
}
