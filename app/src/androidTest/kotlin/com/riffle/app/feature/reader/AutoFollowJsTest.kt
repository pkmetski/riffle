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

    // Distinct in their first 12 chars (the probe keys on a 12-char prefix), so each resolves to its
    // own element rather than colliding on a shared "Adjacent sen…" prefix.
    private val adjacentA = "Alpha narrated line on screen"
    private val adjacentB = "Bravo narrated line on screen"

    // A tall (scroll-mode) document with two sentences one line apart, deep in the page. Used to prove
    // that following flaps back and forth between two ALREADY-VISIBLE adjacent sentences (what a small
    // audio-position jitter across a clip boundary produces) does not bounce the page up and down.
    private val twoAdjacentFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="adjA" style="position:absolute; left:20px; top:2980px; width:320px; height:40px">$adjacentA</div>
            <div id="adjB" style="position:absolute; left:20px; top:3040px; width:320px; height:40px">$adjacentB</div>
          </body>
        </html>
    """.trimIndent()

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
    // The trailing spacer extends the scrollable width well beyond the offright column. On fractional-
    // density emulators getBoundingClientRect() reports coordinates in a space inflated relative to
    // scrollWidth (~1.25× on the 7.1.1 AVD: the offright element at CSS left:5000 measures ~6236), so the
    // column-follow snap computes its target in that inflated space while the scroll clamp is in CSS
    // space. Without slack between the target and end-of-content, the snap clamps short and the target
    // never reaches flush-left. The spacer provides that slack so the invariant under test holds on any
    // density. (Empty, so it never matches the text-probe treewalker.)
    private val shortFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div id="onpage"  style="position:absolute; left:20px;    top:5px; width:240px; height:25px">$onPageText</div>
            <div id="offright" style="position:absolute; left:5000px;  top:5px; width:240px; height:25px">$offRightText</div>
            <div id="spacer"   style="position:absolute; left:8000px;  top:5px; width:240px; height:25px"></div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun scrollModeCentersTheNarratedSentence() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val h = webView.awaitInnerHeight()
            assertTrue("viewport must have a real height", h > 100)

            val result = webView.evalSync(ColumnSnap.autoFollowSnapJs(targetText)).trim('"')
            assertEquals("a sentence in an overflowing document is followed by scrolling → on", "on", result)

            val center = webView.rectCenterY("target")
            assertTrue("sentence should be vertically centred (center=$center, half=${h / 2})", Math.abs(center - h / 2) <= 12)
        }
    }

    @Test
    fun scrollModeDoesNotBounceBetweenAdjacentVisibleSentences() {
        withSizedWebViewFixture(twoAdjacentFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // Centre sentence A first (so A is at mid-viewport and B is one line below it — both well
            // inside the central comfort band, whatever the device's CSS innerHeight works out to).
            val ih = webView.evalSync("window.innerHeight").trim('"').toDouble()
            webView.evalSync("document.scrollingElement.scrollTop=${(2980 + 20 - ih / 2).toInt()}")
            val y0 = webView.scrollY()

            // Simulate the active sentence flapping A → B → A (a backward position blip across the clip
            // boundary). Each follow must leave the page where it is — no re-centre, no up/down bounce.
            webView.evalSync(ColumnSnap.autoFollowSnapJs(adjacentA))
            val ya = webView.scrollY()
            webView.evalSync(ColumnSnap.autoFollowSnapJs(adjacentB))
            val yb = webView.scrollY()
            webView.evalSync(ColumnSnap.autoFollowSnapJs(adjacentA))
            val yc = webView.scrollY()

            val maxDev = maxOf(Math.abs(ya - y0), Math.abs(yb - y0), Math.abs(yc - y0))
            assertTrue(
                "auto-follow must not bounce the page when the active sentence flaps between two " +
                    "already-visible adjacent sentences (y0=$y0 ya=$ya yb=$yb yc=$yc maxDev=$maxDev)",
                maxDev <= 8,
            )
        }
    }

    @Test
    fun scrollModeNeverScrollsHorizontally() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(ColumnSnap.autoFollowSnapJs(targetText))
            assertEquals("auto-follow must not move the page sideways", 0, webView.scrollX())
        }
    }

    @Test
    fun paginatedReturnsOnForAVisibleSentenceWithoutScrolling() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val result = webView.evalSync(ColumnSnap.autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("a fully visible sentence on the current page → on", "on", result)
            assertEquals("paginated mode must not scroll horizontally", 0, webView.scrollX())
            assertEquals("paginated mode must not scroll vertically", 0, webView.scrollY())
        }
    }

    @Test
    fun paginatedSnapsAVisibleSentenceToItsGridAlignedColumn() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // Drift the page a few px off the column grid with the sentence on the current page. Following
            // floors scrollLeft to the sentence's column, which lands flush on the grid — so the page can
            // never rest between two pages (the "shifted left, sliver of the next page showing" readaloud bug).
            webView.evalSync("document.scrollingElement.scrollLeft=10")
            val result = webView.evalSync(ColumnSnap.autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("following a visible sentence returns 'on'", "on", result)
            assertEquals("a visible sentence snaps to its grid-aligned column", 0, webView.scrollX())
        }
    }

    @Test
    fun paginatedFollowsToTheSentencesColumnSymmetrically() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            // With the page scrolled off the grid, following floors to the column that contains the sentence
            // — the same column move it makes whichever way the reader had paged (no asymmetric keep-visible
            // window). Here that lands back on the grid at 0.
            webView.evalSync("document.scrollingElement.scrollLeft=40")
            val result = webView.evalSync(ColumnSnap.autoFollowSnapJs(onPageText)).trim('"')
            assertEquals("following the sentence returns 'on'", "on", result)
            assertEquals("follows to the sentence's grid-aligned column", 0, webView.scrollX())
        }
    }

    @Test
    fun paginatedSnapsToTheColumnContainingTheSentence() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val iw = webView.innerWidth()
            assertTrue("viewport must have a real width", iw > 100)

            val result = webView.evalSync(ColumnSnap.autoFollowSnapJs(offRightText)).trim('"')
            assertEquals("a sentence on another page is followed by snapping → on", "on", result)

            // The invariant: following an off-page sentence lands the page ON the column grid — scrollLeft
            // a whole multiple of innerWidth — so the page never rests between two columns. Assert that in
            // scroll space directly. (The earlier getBoundingClientRect()-based proxy assumed rect space
            // and scroll space coincide; on fractional-density emulators they differ by a constant factor,
            // so the proxy reported the column off-grid even when scrollLeft was a clean multiple. The
            // spacer in shortFixture keeps the target off the end-of-content clamp so the snap reaches a
            // clean column rather than clamping to a non-grid max.)
            val sx = webView.scrollX()
            assertTrue("snapping a next-page sentence must move the page", sx > 0)
            assertEquals("page must land on the column grid (scrollX=$sx, iw=$iw)", 0, sx % iw)
        }
    }

    @Test
    fun returnsOffForTextNotOnThePage() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("off", webView.evalSync(ColumnSnap.autoFollowSnapJs("Zzz nonexistent sentence text")).trim('"'))
        }
    }

    @Test
    fun emptyTextDisablesTheProbe() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("empty/unknown text → off (caller falls back to go())", "off", webView.evalSync(ColumnSnap.autoFollowSnapJs("")).trim('"'))
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
