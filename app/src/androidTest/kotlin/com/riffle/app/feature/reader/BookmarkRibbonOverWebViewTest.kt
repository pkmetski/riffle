package com.riffle.app.feature.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for: the corner bookmark ribbon is hidden by Readium's WebView in the steady
 * state, because the WebView composites from its own hardware RenderNode and paints over sibling
 * Compose children. The fix forces the ribbon into its own GPU layer via `Modifier.graphicsLayer`,
 * which Compose then composites on top of the WebView surface.
 *
 * This test reproduces the same layout shape as [EpubReaderScreen]: a Box containing an
 * [AndroidView] hosting a real [WebView] full-bleed below, with the [CornerBookmarkIndicator]
 * aligned to the top-end above. A pixel sampled at the ribbon's location must be the active brown
 * fill, NOT the WebView's white background — which is what the bug looked like on device.
 *
 * Without `graphicsLayer` in the indicator's modifier chain, the WebView's RenderNode wins the
 * compositing step and the ribbon pixels never reach the screen even though the composable lays
 * out at the right bounds (the original bug).
 *
 * Capture path: [android.app.UiAutomation.takeScreenshot] reads back the actual composited
 * display surface (the same surface the user sees), so the WebView's hardware RenderNode layer
 * IS included — that's what makes this a valid regression test. Available since API 18, so it
 * works on the project's minimum-supported Android 7.1.1 / API 25. Compose's `captureToImage()`
 * uses `PixelCopy.request(Window, …)` which is API 26+ and would NoSuchMethodError on 7.1.1.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkRibbonOverWebViewTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun bookmarkRibbon_rendersAboveWebView() {
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.WHITE)
                            loadDataWithBaseURL(
                                null,
                                "<html><body style='background:#ffffff;margin:0;'></body></html>",
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                )
                CornerBookmarkIndicator(
                    isBookmarked = true,
                    isVisible = true,
                    onToggle = {},
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp)
                        .graphicsLayer { },
                )
            }
        }

        // Wait for the bookmark fill animation (180ms tween) + spring scale to settle.
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1_000L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Remove bookmark").assertExists()

        // Read back the composited display surface and scan the top-end region (where the
        // CornerBookmarkIndicator is aligned) for any pixel matching the ribbon's brown fill.
        // A scan is more robust than coordinate-cropping: window-vs-screen offsets (status bar)
        // and per-device system-bar themes don't matter — we just need ONE brown pixel to exist
        // anywhere in the indicator's quadrant. If the WebView were hiding the ribbon (the bug
        // this test guards), the entire top-end region would read solid white instead.
        val screen: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val xStart = screen.width / 2
        val yEnd = screen.height / 4
        var brownPixels = 0
        for (yy in 0 until yEnd) {
            for (xx in xStart until screen.width) {
                val px = screen.getPixel(xx, yy)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                // Active fill is 0xFFB5440E — a saturated brown. Reject anything that looks like
                // a white WebView background (255, 255, 255) or grey status-bar chrome.
                if (r in 120..220 && g in 30..120 && b in 0..80) brownPixels++
            }
        }
        // The ribbon is 24×32dp ≈ 96×128px at 4x density; a handful of brown pixels in the scan
        // region is enough to prove it rendered. Demand a small minimum so a single stray red
        // pixel from the system theme couldn't pass the test.
        assertTrue(
            "Bookmark ribbon's brown fill not visible in the top-end region " +
                "(found $brownPixels brown pixels) — the WebView is hiding the ribbon. " +
                "Verify the indicator still has Modifier.graphicsLayer in its chain.",
            brownPixels >= 50,
        )
    }
}
