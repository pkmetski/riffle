package com.riffle.app.feature.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards [ColumnSnap.SETTLE_SNAP_INSTALL_JS] — the at-rest column-snap backstop installed on every reader page.
 *
 * Note: the SNAP itself (rounding an off-grid paginated page to the nearest column) can't be exercised
 * here — on the API-25 WebView a synthetic fixture has no real horizontal scroll viewport (setting
 * scrollLeft bumps window.scrollX but does not move geometry; see [AutoFollowOffGridReproTest]). So this
 * verifies the install is valid + idempotent and that the listener never DISTURBS a page it shouldn't:
 * an already-aligned page and vertical (scroll) mode must be left exactly as they are.
 */
@RunWith(AndroidJUnit4::class)
class SettleSnapInstallTest {

    private val shortFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div style="position:absolute; left:20px; top:5px; width:240px; height:25px">Aligned page content</div>
          </body>
        </html>
    """.trimIndent()

    private val tallFixture = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div style="position:absolute; left:20px; top:3000px; width:240px; height:25px">Deep content</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun installsOnceAndIsIdempotent() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(ColumnSnap.SETTLE_SNAP_INSTALL_JS)
            assertEquals("installed flag set", "true", webView.evalSync("window.__riffleSettleSnapInstalled").trim('"'))
            // Re-injecting must not throw and must remain installed (idempotent).
            webView.evalSync(ColumnSnap.SETTLE_SNAP_INSTALL_JS)
            assertEquals("still installed after re-inject", "true", webView.evalSync("window.__riffleSettleSnapInstalled").trim('"'))
        }
    }

    @Test
    fun doesNotDisturbAnAlreadyAlignedPage() {
        withSizedWebViewFixture(shortFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(ColumnSnap.SETTLE_SNAP_INSTALL_JS)
            // Page is at the grid origin (scrollLeft 0). A settle pass must leave it untouched.
            webView.evalSync("window.dispatchEvent(new Event('scroll'))")
            Thread.sleep(250) // > the 120ms settle debounce
            assertEquals("aligned page must not be moved horizontally", 0, webView.scrollX())
            assertEquals("aligned page must not be moved vertically", 0, webView.scrollY())
        }
    }

    @Test
    fun leavesVerticalScrollModeUntouched() {
        withSizedWebViewFixture(tallFixture, widthPx = 1080, heightPx = 1600) { webView ->
            webView.awaitInnerHeight()
            webView.evalSync(ColumnSnap.SETTLE_SNAP_INSTALL_JS)
            webView.evalSync("window.scrollTo(0, 300)")
            webView.evalSync("window.dispatchEvent(new Event('scroll'))")
            Thread.sleep(250)
            assertTrue("vertical scroll position must be preserved", Math.abs(webView.scrollY() - 300) <= 2)
            assertEquals("scroll mode must never gain a horizontal offset", 0, webView.scrollX())
        }
    }

    private fun WebView.scrollX(): Int = evalSync("window.scrollX").trim('"').toDouble().toInt()
    private fun WebView.scrollY(): Int = evalSync("window.scrollY").trim('"').toDouble().toInt()
}
