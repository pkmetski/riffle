package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [ColumnSnap.snapToEndColumnJs] — the probe a backward chapter turn runs so the previous
 * chapter's LAST page lands as "one page back".
 *
 * The bug it guards: the swipe-back-at-a-chapter-start route used a raw `go()` to a `progression = 1.0`
 * locator, which resolves 1.0 against the freshly loaded chapter's PRE-reflow column count and is then
 * left stranded several columns short of the true end once the typography reflow adds columns — the
 * "previous chapter overshoots 4-5 pages back" report. The end-snap must drive the page onto the last
 * column regardless of where the navigation landed.
 */
@RunWith(AndroidJUnit4::class)
class BackwardChapterSnapJsTest {

    // A page far wider than the viewport → paginated, with many columns. The mid-page scrollLeft the test
    // sets before snapping stands in for the pre-reflow overshoot; the snap must reach the last column.
    private val wideFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div style="position:absolute; left:20px;   top:5px; width:240px; height:25px">First page content</div>
            <div style="position:absolute; left:7000px; top:5px; width:240px; height:25px">Last page content</div>
          </body>
        </html>
    """.trimIndent()

    // A document much taller than the viewport → scroll (karaoke) mode. The backward end-snap should land
    // at the bottom, never gaining a horizontal offset.
    private val tallFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div style="position:absolute; left:20px; top:3000px; width:240px; height:25px">Deep content</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun paginatedBackwardTurnLandsOnTheLastColumn() {
        withSizedWebViewFixture(wideFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val iw = webView.innerWidth()
            assertTrue("viewport must have a real width", iw > 100)
            val sw = webView.scrollWidth()
            assertTrue("fixture must be much wider than one page (sw=$sw iw=$iw)", sw > iw * 4)

            // Stand in for the pre-reflow overshoot: a go(progression=1.0) that left the page a few
            // columns short of the end. The backward-turn snap must drive it onto the LAST column.
            webView.evalSync("document.scrollingElement.scrollLeft=$iw")
            webView.evalSync(ColumnSnap.snapToEndColumnJs())

            val sx = webView.scrollX()
            assertEquals("lands flush on the column grid (sx=$sx iw=$iw)", 0, sx % iw)
            assertTrue(
                "lands on the LAST page — no whole column remains to the right (sx=$sx sw=$sw iw=$iw)",
                sx + iw > sw - iw,
            )
            assertTrue("moves forward to the end from the overshoot (sx=$sx iw=$iw)", sx > iw)
        }
    }

    @Test
    fun landedAtEndDetectsReadiumsEndPlacement() {
        withSizedWebViewFixture(wideFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val iw = webView.innerWidth()
            val sw = webView.scrollWidth()

            // Column 0 (a forward turn / TOC chapter-top landing) is NOT an end landing.
            webView.evalSync("document.scrollingElement.scrollLeft=0")
            assertEquals("column 0 is not the end", "false", webView.evalSync(ColumnSnap.LANDED_AT_END_JS).trim('"'))

            // A mid-resource position (a resume to the middle) is NOT an end landing.
            webView.evalSync("document.scrollingElement.scrollLeft=$iw")
            assertEquals("a middle column is not the end", "false", webView.evalSync(ColumnSnap.LANDED_AT_END_JS).trim('"'))

            // The last column (Readium's backward-turn placement) IS an end landing.
            webView.evalSync("document.scrollingElement.scrollLeft=${sw - iw}")
            assertEquals("the last column is the end", "true", webView.evalSync(ColumnSnap.LANDED_AT_END_JS).trim('"'))
        }
    }

    @Test
    fun landedAtEndIsFalseForASinglePageResource() {
        // A resource that fits one page has no backward-overshoot to correct; never report an end landing.
        withSizedWebViewFixture(tallFixture.replace("height:6000px", "height:40px"), widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("a single-page resource is not an end landing", "false", webView.evalSync(ColumnSnap.LANDED_AT_END_JS).trim('"'))
        }
    }

    @Test
    fun landedAtEndIsFalseInScrollMode() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("scroll mode has no column grid → not an end landing", "false", webView.evalSync(ColumnSnap.LANDED_AT_END_JS).trim('"'))
        }
    }

    @Test
    fun scrollModeBackwardTurnLandsAtTheBottom() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val h = webView.awaitInnerHeight()
            webView.evalSync("document.scrollingElement.scrollTop=0")
            webView.evalSync(ColumnSnap.snapToEndColumnJs())
            val sy = webView.scrollY()
            val sh = webView.scrollHeight()
            assertTrue("scroll mode lands at the bottom (sy=$sy sh=$sh h=$h)", sy + h >= sh - 4)
            assertEquals("scroll mode must never gain a horizontal offset", 0, webView.scrollX())
        }
    }

    private fun WebView.innerWidth(): Int = evalSync("window.innerWidth").trim('"').toDouble().toInt()
    private fun WebView.scrollWidth(): Int = evalSync("document.scrollingElement.scrollWidth").trim('"').toDouble().toInt()
    private fun WebView.scrollHeight(): Int = evalSync("document.scrollingElement.scrollHeight").trim('"').toDouble().toInt()
    private fun WebView.scrollX(): Int = evalSync("window.scrollX").trim('"').toDouble().toInt()
    private fun WebView.scrollY(): Int = evalSync("window.scrollY").trim('"').toDouble().toInt()
}
