package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the page-follow probe ([autoFollowSnapJs]) against a real, sized WebView. The probe locates
 * the narrated sentence by its TEXT (Readium strips the media-overlay span ids), so these fixtures
 * carry distinctive sentence text rather than relying on element ids. Backs the two
 * "while the player is playing" behaviours:
 *  - the page follows the narrated sentence (scroll mode centres it vertically; paginated mode snaps
 *    scrollLeft to the column that contains the sentence's start), and
 *  - paginated snaps land on the page grid (scrollLeft a whole multiple of innerWidth), so the page
 *    never rests between two columns.
 */
@RunWith(AndroidJUnit4::class)
class AutoFollowJsTest {

    private val onPageText = "Onpage visible sentence to follow"
    private val offRightText = "Offright next page sentence here"
    private val targetText = "Narrated target sentence deep in the page"

    // A document much taller than the viewport → scroll (karaoke) mode. The narrated sentence sits
    // far down the page so following it requires a real vertical scroll.
    private val tallFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="target" style="position:absolute; left:20px; top:3000px; width:300px; height:40px">$targetText</div>
          </body>
        </html>
    """.trimIndent()

    // A viewport-sized page → paginated mode. The on-page sentence is fully visible; the off-page one
    // sits far to the right (a later column/page), so following it requires a horizontal column snap.
    private val shortFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div id="onpage"  style="position:absolute; left:20px;    top:5px; width:240px; height:25px">$onPageText</div>
            <div id="offright" style="position:absolute; left:5000px;  top:5px; width:240px; height:25px">$offRightText</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun scrollModeCentersTheNarratedSentence() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val h = webView.awaitInnerHeight()
            assertTrue("viewport must have a real height", h > 100)

            val result = webView.evalSync(autoFollowSnapJs(targetText)).trim('"')
            assertEquals("a sentence in an overflowing document is followed by scrolling → on", "on", result)

            val center = webView.rectCenterY("target")
            assertTrue("sentence should be vertically centred (center=$center, half=${h / 2})", Math.abs(center - h / 2) <= 12)
        }
    }

    @Test
    fun scrollModeNeverScrollsHorizontally() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(autoFollowSnapJs(targetText))
            assertEquals("auto-follow must not move the page sideways", 0, webView.scrollX())
        }
    }

    @Test
    fun paginatedReturnsOnForAVisibleSentenceWithoutScrolling() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val result = webView.evalSync(autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("a fully visible sentence on the current page → on", "on", result)
            assertEquals("paginated mode must not scroll horizontally", 0, webView.scrollX())
            assertEquals("paginated mode must not scroll vertically", 0, webView.scrollY())
        }
    }

    @Test
    fun paginatedKeepsAVisibleSentenceInPlaceWithoutReSnapping() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // Drift the page a few px off the column grid, with the on-page sentence still visible. The
            // keep-visible follow must leave the page exactly where it is (the old always-snap behaviour
            // would have pulled scrollLeft back to the grid at 0 — the "jump" when playback starts).
            webView.evalSync("document.scrollingElement.scrollLeft=10")
            val result = webView.evalSync(autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("a visible sentence keeps the page 'on'", "on", result)
            assertEquals("a visible sentence must be left in place, not re-snapped to the grid", 10, webView.scrollX())
        }
    }

    @Test
    fun paginatedDoesNotSnapBackForASentenceWrappingInFromThePreviousColumn() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // Drift the page forward so the on-page sentence's START sits just off the left edge while its
            // body stays visible on this page — the column-spanning case (a narrated sentence wrapped in
            // from the previous column). Keep-visible must leave the page put; the old probe snapped
            // scrollLeft back to the start's column — the page "jump" when playback starts on such a line.
            webView.evalSync("document.scrollingElement.scrollLeft=40")
            val result = webView.evalSync(autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("a sentence wrapping in from the previous column stays on the page", "on", result)
            assertEquals("a wrapped-in sentence must not snap the page back a column", 40, webView.scrollX())
        }
    }

    @Test
    fun reflowAnchorCaptureReturnsTheTopOfPageLine() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // The capture returns a prefix of the line at the top of the current page — the anchor the
            // reserve reflow pins back. It must be the on-page line, never the off-page (next-column) one.
            val anchor = webView.evalSync(reflowAnchorCaptureJs()).trim('"')
            assertTrue("a non-empty anchor is captured", anchor.isNotEmpty())
            assertTrue("anchor is the on-page line (got '$anchor')", onPageText.startsWith(anchor))
        }
    }

    @Test
    fun paginatedSnapsToTheColumnContainingTheSentence() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val iw = webView.innerWidth()
            assertTrue("viewport must have a real width", iw > 100)

            val result = webView.evalSync(autoFollowSnapJs(offRightText)).trim('"')
            assertEquals("a sentence on another page is followed by snapping → on", "on", result)

            // After the snap the sentence's column is flush-left: its first char's client-left equals
            // (absX mod iw), which lands in [0, iw) exactly when scrollLeft is a whole multiple of iw
            // — i.e. the page is on the column grid, not resting between two columns.
            val left = webView.rectLeft("offright")
            assertTrue("sentence should be snapped onto the page grid (left=$left, iw=$iw)", left in 0 until iw)
            assertTrue("snapping a next-page sentence must move the page", webView.scrollX() > 0)
        }
    }

    @Test
    fun returnsOffForTextNotOnThePage() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("off", webView.evalSync(autoFollowSnapJs("Zzz nonexistent sentence text")).trim('"'))
        }
    }

    @Test
    fun emptyTextDisablesTheProbe() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("empty/unknown text → off (caller falls back to go())", "off", webView.evalSync(autoFollowSnapJs("")).trim('"'))
        }
    }

    // ---- helpers ----

    private fun WebView.rectCenterY(id: String): Int =
        evalSync("(function(){var r=document.getElementById('$id').getBoundingClientRect();return Math.round((r.top+r.bottom)/2);})()")
            .trim('"').toDouble().toInt()

    private fun WebView.rectLeft(id: String): Int =
        evalSync("(function(){return Math.round(document.getElementById('$id').getBoundingClientRect().left);})()")
            .trim('"').toDouble().toInt()

    private fun WebView.innerWidth(): Int = evalSync("window.innerWidth").trim('"').toDouble().toInt()

    private fun WebView.scrollX(): Int = evalSync("window.scrollX").trim('"').toDouble().toInt()
    private fun WebView.scrollY(): Int = evalSync("window.scrollY").trim('"').toDouble().toInt()
}
