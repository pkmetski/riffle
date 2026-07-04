package com.riffle.app.feature.reader

import android.content.Context
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [ChapterWebView] explicitly overrides the WebView settings that OEM manufacturers
 * can set differently from AOSP defaults, which would break text sizing in Continuous mode.
 *
 * Root cause: some OEM WebViews (e.g. Samsung) default `useWideViewPort = true` and/or
 * automatically apply the system font-scale to `textZoom`. Without explicit overrides:
 *   - `useWideViewPort = true` + no `<meta name="viewport">` → 980px layout viewport
 *   - `loadWithOverviewMode = true` → page zoomed to ~42% of normal size to fit the screen
 *   - Text in Continuous mode appears far smaller than in Scroll/Paginated mode (which Readium
 *     fixes via `readium-reflowable.js` injecting a `width=device-width` viewport meta and setting
 *     `textZoom` explicitly to implement its own font-size preference).
 *
 * The three settings tests are regression guards: if someone removes an explicit override in
 * [ChapterWebView.init], the tests fail immediately rather than waiting for a real-device report.
 * The viewport behaviour test confirms the observable outcome on a running WebView.
 */
@RunWith(AndroidJUnit4::class)
class ChapterWebViewSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    // ── WebView settings (regression guards) ─────────────────────────────────────

    @Test
    fun useWideViewPortIsExplicitlyFalse() {
        var value = true
        instrumentation.runOnMainSync {
            val wv = ChapterWebView(context)
            value = wv.settings.useWideViewPort
            wv.destroy()
        }
        assertFalse(
            "useWideViewPort must be false so EPUB chapters without a <meta name=viewport> " +
                "use the WebView's own CSS-pixel width as the layout viewport, " +
                "not the 980px OEM wide-viewport default that scales the whole page to ~42%",
            value,
        )
    }

    @Test
    fun loadWithOverviewModeIsExplicitlyFalse() {
        var value = true
        instrumentation.runOnMainSync {
            val wv = ChapterWebView(context)
            value = wv.settings.loadWithOverviewMode
            wv.destroy()
        }
        assertFalse(
            "loadWithOverviewMode must be false — in combination with a wide layout viewport " +
                "it zooms the whole page down to fit the screen, making text far smaller than " +
                "in Scroll/Paginated mode where Readium pins the viewport to device-width",
            value,
        )
    }

    @Test
    fun textZoomIsPinnedTo100() {
        var value = -1
        instrumentation.runOnMainSync {
            val wv = ChapterWebView(context)
            value = wv.settings.textZoom
            wv.destroy()
        }
        assertEquals(
            "textZoom must be 100 so OEM-applied system font-scale cannot shrink Continuous mode " +
                "text below Scroll/Paginated mode (Readium also sets textZoom explicitly to 100 for " +
                "its default font-size, keeping its text unaffected by the system accessibility setting)",
            100,
            value,
        )
    }

    /**
     * Regression guard for the chapter-boundary blank flash: in Continuous mode we stack many
     * [ChapterWebView]s inside a NestedScrollView, so each is typically only partially visible.
     * Without `OFF_SCREEN_PRERASTER`, Chromium's tile pipeline can drop or lazily re-raster the
     * off-screen portion — when a chapter's tail scrolls above the viewport top and the user
     * scrolls back down, the last visible line briefly renders as blank until the tile is
     * re-rasterized. See `reference_continuous_chapter_boundary_blank_flash` for the full
     * investigation.
     */
    @Test
    fun offscreenPreRasterIsEnabledWhenSupported() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.OFF_SCREEN_PRERASTER,
            )
        ) {
            return
        }
        var value = false
        instrumentation.runOnMainSync {
            val wv = ChapterWebView(context)
            value = androidx.webkit.WebSettingsCompat.getOffscreenPreRaster(wv.settings)
            wv.destroy()
        }
        assertTrue(
            "OFF_SCREEN_PRERASTER must be enabled on every ChapterWebView so a chapter's " +
                "off-screen tail stays rasterized while stacked in Continuous mode — otherwise the " +
                "trailing text of the previous chapter briefly blanks out mid-scroll at every " +
                "chapter boundary. Do not remove this setter without a comparable substitute.",
            value,
        )
    }

    // ── Viewport behaviour ───────────────────────────────────────────────────────

    /**
     * Negative control: a plain [WebView] with `useWideViewPort = true` and no
     * `<meta name="viewport">` must produce a layout viewport of ~980 CSS px.
     * This proves the viewport mechanism under test is actually detectable on this device/WebView
     * version — if this test fails, the positive test below is also unreliable.
     */
    @Test
    fun plainWebViewWithWideViewPortProduces980pxLayoutViewport() {
        // useWideViewPort must be set BEFORE loading the page to take effect.
        val html = "<html><head></head><body><p>text</p></body></html>"
        val physicalWidth = 1080
        val physicalHeight = 1920
        val ready = CountDownLatch(1)
        val holder = arrayOfNulls<WebView>(1)
        instrumentation.runOnMainSync {
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.useWideViewPort = true  // the OEM-buggy setting
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = ready.countDown()
            }
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(physicalWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(physicalHeight, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, physicalWidth, physicalHeight)
            holder[0] = wv
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        assertTrue("WebView page did not finish loading", ready.await(10, TimeUnit.SECONDS))
        val wv = holder[0]!!
        instrumentation.runOnMainSync {
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(physicalWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(physicalHeight, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, physicalWidth, physicalHeight)
        }
        try {
            wv.awaitInnerHeight(timeoutMs = 5_000)
            val clientWidth = wv.evalSync("document.documentElement.clientWidth")
                .trim('"').toDoubleOrNull() ?: 0.0
            assertEquals(
                "A WebView with useWideViewPort=true and no viewport meta must use the " +
                    "980px default layout viewport — this validates the negative control",
                980.0,
                clientWidth,
                2.0,
            )
        } finally {
            instrumentation.runOnMainSync { wv.destroy() }
        }
    }

    /**
     * Loads an HTML page with **no** `<meta name="viewport">` into a [ChapterWebView] — the
     * real-world EPUB case that triggered the bug — and verifies that
     * `document.documentElement.clientWidth` equals the WebView's own CSS-pixel width, not 980.
     *
     * The negative-control test above confirms 980 IS the result when `useWideViewPort = true`,
     * making this test meaningful on any device/WebView version, not just OEM images.
     */
    @Test
    fun layoutViewportEqualsWebViewCssPxWidthNotWideViewportDefault() {
        val html = "<html><head></head><body><p>text</p></body></html>"
        val physicalWidth = 1080
        val physicalHeight = 1920
        withChapterWebViewFixture(html, widthPx = physicalWidth, heightPx = physicalHeight) { wv ->
            wv.awaitInnerHeight(timeoutMs = 5_000)
            // Read DPR from the WebView's own JS so we tolerate any screen density on CI or
            // real devices. CSS pixels = physicalWidth / devicePixelRatio.
            val dpr = wv.evalSync("window.devicePixelRatio").trim('"').toDoubleOrNull() ?: 1.0
            val expectedCssPx = physicalWidth / dpr
            val clientWidth = wv.evalSync("document.documentElement.clientWidth")
                .trim('"').toDoubleOrNull() ?: 0.0
            assertEquals(
                "document.documentElement.clientWidth must equal the WebView's CSS-pixel width " +
                    "(~${expectedCssPx.toInt()} px on this device), " +
                    "not the 980px wide-viewport default",
                expectedCssPx,
                clientWidth,
                2.0, // 2 CSS-px tolerance for rounding at non-integer display densities
            )
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    /**
     * Loads [html] into a [ChapterWebView] laid out at [widthPx] × [heightPx] physical pixels,
     * waits for `onPageFinished`, runs [block], then destroys the view.
     *
     * Uses [ChapterWebView.onPageFinished] (the public callback) rather than replacing the
     * internal `WebViewClient` so the real production setup — including `shouldInterceptRequest`
     * and all registered JS interfaces — is exercised unchanged.
     */
    private fun withChapterWebViewFixture(
        html: String,
        widthPx: Int,
        heightPx: Int,
        block: (ChapterWebView) -> Unit,
    ) {
        val ready = CountDownLatch(1)
        val holder = arrayOfNulls<ChapterWebView>(1)
        instrumentation.runOnMainSync {
            val wv = ChapterWebView(context)
            wv.onPageFinished = { ready.countDown() }
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, widthPx, heightPx)
            holder[0] = wv
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        assertTrue("ChapterWebView page did not finish loading within 10 s", ready.await(10, TimeUnit.SECONDS))
        val wv = holder[0]!!
        // Re-apply layout after load so the viewport reflects the fixed size (mirrors withSizedWebViewFixture).
        instrumentation.runOnMainSync {
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, widthPx, heightPx)
        }
        try {
            block(wv)
        } finally {
            instrumentation.runOnMainSync { wv.destroy() }
        }
    }
}
