package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the page-follow probe ([autoFollowSnapJs]) against a real, sized WebView. Backs the two
 * "while the player is playing" behaviours:
 *  - the page must follow the narrated sentence (scroll mode centres it; paginated mode reports
 *    "off" so the reader snaps to its page), and
 *  - the page must never drift sideways — the probe only ever scrolls the Y axis.
 */
@RunWith(AndroidJUnit4::class)
class AutoFollowJsTest {

    // A document much taller than the viewport → scroll (karaoke) mode. The narrated sentence sits
    // far down the page so following it requires a real vertical scroll.
    private val tallFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="target" style="position:absolute; left:20px; top:3000px; width:200px; height:40px">narrated</div>
          </body>
        </html>
    """.trimIndent()

    // A viewport-sized page → paginated mode. #onpage is fully visible; #offright sits far to the
    // right (as if on the next column/page); #offleft far to the left (previous page).
    private val shortFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div id="onpage"  style="position:absolute; left:20px;    top:5px; width:80px; height:25px">a</div>
            <div id="offright" style="position:absolute; left:5000px;  top:5px; width:80px; height:25px">b</div>
            <div id="offleft"  style="position:absolute; left:-5000px; top:5px; width:80px; height:25px">c</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun scrollModeCentersTheNarratedSentence() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            val h = webView.awaitInnerHeight()
            assertTrue("viewport must have a real height", h > 100)

            val result = webView.evalSync(autoFollowSnapJs("target")).trim('"')
            assertEquals("a sentence in an overflowing document is followed by scrolling → on", "on", result)

            val center = webView.rectCenterY("target")
            // The probe scrolls so the sentence's vertical centre lands at the viewport centre
            // (within the probe's own >8px dead-band, plus rounding).
            assertTrue("sentence should be vertically centred (center=$center, half=${h / 2})", Math.abs(center - h / 2) <= 12)
        }
    }

    @Test
    fun scrollModeNeverScrollsHorizontally() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(autoFollowSnapJs("target"))
            assertEquals("auto-follow must not move the page sideways", 0, webView.scrollX())
        }
    }

    @Test
    fun paginatedReturnsOnForAVisibleSentenceWithoutScrolling() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            val result = webView.evalSync(autoFollowSnapJs("onpage")).trim('"')
            assertEquals("a fully visible sentence on the current page → on", "on", result)
            assertEquals("paginated mode must not scroll horizontally", 0, webView.scrollX())
            assertEquals("paginated mode must not scroll vertically", 0, webView.scrollY())
        }
    }

    @Test
    fun paginatedReturnsOffForASentenceOnAnotherPage() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals(
                "a sentence off to the right (next page) → off, so the reader snaps to its page",
                "off",
                webView.evalSync(autoFollowSnapJs("offright")).trim('"'),
            )
            assertEquals(
                "a sentence off to the left (previous page) → off",
                "off",
                webView.evalSync(autoFollowSnapJs("offleft")).trim('"'),
            )
            assertEquals("probing an off-page sentence must not scroll horizontally", 0, webView.scrollX())
        }
    }

    @Test
    fun returnsOffForAnUnknownFragment() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            assertEquals("off", webView.evalSync(autoFollowSnapJs("does-not-exist")).trim('"'))
        }
    }

    // ---- helpers ----

    private fun WebView.rectCenterY(id: String): Int =
        evalSync("(function(){var r=document.getElementById('$id').getBoundingClientRect();return Math.round((r.top+r.bottom)/2);})()")
            .trim('"').toDouble().toInt()

    private fun WebView.scrollX(): Int = evalSync("window.scrollX").trim('"').toDouble().toInt()
    private fun WebView.scrollY(): Int = evalSync("window.scrollY").trim('"').toDouble().toInt()
}
