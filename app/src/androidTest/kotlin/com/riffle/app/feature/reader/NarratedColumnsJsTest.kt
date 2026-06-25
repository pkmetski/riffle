package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the intra-sentence column geometry ([ColumnSnap.measureNarratedColumnsJs] /
 * [ColumnSnap.snapNarratedColumnJs]) against a real, sized WebView — the device half of the
 * sentence-spanning-page follow whose decision half is unit-tested in NarratedColumnProgressionTest.
 *
 * Assertions are deliberately STRUCTURAL (column count, monotonicity, last≈1.0, snap ordering) rather
 * than exact fractions: on the fractional-density test AVD getClientRects() reports coordinates in a
 * space inflated relative to scroll space (~1.25×, see AutoFollowJsTest), which skews the per-column
 * width split but never the invariants that matter — a spanning sentence still measures ≥2 columns,
 * and snapping to a later column still moves the page right onto the grid.
 */
@RunWith(AndroidJUnit4::class)
class NarratedColumnsJsTest {

    // A short sentence that fits on one line → one column.
    private val singleColText = "Short single column line"

    // A long sentence forced to straddle the column-0/column-1 boundary: an 82vh spacer fills most of
    // the first viewport-wide column, so this sentence starts near its bottom and wraps into the next
    // column. Distinctive in its first 12 chars so the text probe resolves it.
    private val spanningText =
        "Spanning narrated sentence that is deliberately long enough to overflow the bottom of the " +
            "first column and continue wrapping onto the following column so its rendered rectangles " +
            "fall into two distinct paginated columns of the document under test here today now."

    // Viewport-wide CSS columns with a fixed height, so overflow flows into horizontal columns
    // (paginated mode, not scroll mode). The spacer deterministically pushes the spanning sentence
    // across the first column boundary.
    private val spanningFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0">
            <div style="height:100vh; columns:100vw; column-gap:0; column-fill:auto; font-size:18px; line-height:26px">
              <div style="height:82vh"></div>
              <span>$spanningText</span>
            </div>
          </body>
        </html>
    """.trimIndent()

    // One viewport-wide column, a single short sentence near the top → exactly one column.
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
    fun measuresASpanningSentenceAsAtLeastTwoColumns() {
        withSizedWebViewFixture(spanningFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val fr = webView.evalSync(ColumnSnap.measureNarratedColumnsJs(spanningText)).asFractions()

            assertTrue("a sentence wrapping across the column boundary must measure ≥2 columns (got $fr)", fr.size >= 2)
            // Cumulative width fractions: strictly increasing, ending at the whole sentence (1.0).
            for (i in 1 until fr.size) {
                assertTrue("fractions must strictly increase (got $fr)", fr[i] > fr[i - 1])
            }
            assertEquals("the last cumulative fraction is the whole sentence", 1.0, fr.last(), 0.0001)
            assertTrue("every fraction is in (0,1]", fr.all { it > 0.0 && it <= 1.0001 })
        }
    }

    @Test
    fun measuresASingleLineSentenceAsOneColumn() {
        withSizedWebViewFixture(singleColumnFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val fr = webView.evalSync(ColumnSnap.measureNarratedColumnsJs(singleColText)).asFractions()
            assertEquals("a one-line sentence occupies a single column (got $fr)", 1, fr.size)
            assertEquals(1.0, fr[0], 0.0001)
        }
    }

    @Test
    fun snappingToALaterColumnMovesThePageRightOntoTheGrid() {
        withSizedWebViewFixture(spanningFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val iw = webView.evalSync("window.innerWidth").trim('"').toDouble().toInt()
            assertTrue("viewport must have a real width", iw > 100)
            val lastColumn = webView.evalSync(ColumnSnap.measureNarratedColumnsJs(spanningText)).asFractions().size - 1

            assertEquals("snapping returns on", "on", webView.evalSync(ColumnSnap.snapNarratedColumnJs(spanningText, 0)).trim('"'))
            val x0 = webView.scrollX()
            assertEquals("column 0 lands flush on the grid", 0, x0 % iw)

            webView.evalSync(ColumnSnap.snapNarratedColumnJs(spanningText, lastColumn))
            val xLast = webView.scrollX()
            assertTrue("a later column is to the right of column 0 (x0=$x0 xLast=$xLast)", xLast > x0)
            assertEquals("the later column also lands flush on the grid (xLast=$xLast iw=$iw)", 0, xLast % iw)
        }
    }

    @Test
    fun snapClampsAnOutOfRangeColumnIndex() {
        withSizedWebViewFixture(spanningFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val iw = webView.evalSync("window.innerWidth").trim('"').toDouble().toInt()
            // Far past the last column — clamps to the last rather than scrolling into empty space.
            assertEquals("on", webView.evalSync(ColumnSnap.snapNarratedColumnJs(spanningText, 99)).trim('"'))
            val clamped = webView.scrollX()
            val lastColumn = webView.evalSync(ColumnSnap.measureNarratedColumnsJs(spanningText)).asFractions().size - 1
            webView.evalSync(ColumnSnap.snapNarratedColumnJs(spanningText, lastColumn))
            assertEquals("an out-of-range index clamps to the last column", webView.scrollX(), clamped)
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

    // evaluateJavascript wraps a returned JSON string in quotes (the inner numeric array has no quotes
    // to escape); strip them and parse the array.
    private fun String.asFractions(): List<Double> {
        val json = trim('"')
        val arr = JSONArray(json)
        return List(arr.length()) { arr.getDouble(it) }
    }

    private fun WebView.scrollX(): Int = evalSync("window.scrollX").trim('"').toDouble().toInt()
}
