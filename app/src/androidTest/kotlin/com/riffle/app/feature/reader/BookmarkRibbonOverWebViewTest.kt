package com.riffle.app.feature.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.Window
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for: the corner bookmark ribbon is hidden by Readium's WebView in the steady
 * state, because the WebView composites from its own hardware RenderNode and paints over sibling
 * Compose children. The fix forces the ribbon into its own GPU layer via `Modifier.graphicsLayer`,
 * which Compose then composites on top of the WebView surface.
 *
 * This test reproduces the same layout shape as [EpubReaderScreen]: a Box containing an
 * [AndroidView] hosting a real [WebView] full-bleed below, with the [CornerBookmarkIndicator]
 * aligned to the top-end above. The ribbon's brown fill must be present anywhere in the top-end
 * region of the captured surface — if the WebView were hiding it (the original bug), the whole
 * region would read solid white.
 *
 * Capture path: [PixelCopy.request] against the activity window's Surface (obtained via
 * reflection on `ViewRootImpl.mSurface`). Available since API 24, works in headless emulators
 * because it reads from the buffer SurfaceFlinger composites into — the same buffer that drives
 * the WebView's hardware RenderNode promotion that the bug depends on. Compose's
 * `captureToImage()` uses `PixelCopy.request(Window, …)` instead, which is API 26+ and would
 * `NoSuchMethodError` on the project's minimum-supported Android 7.1.1.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkRibbonOverWebViewTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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

        val window = composeTestRule.activity.window
        val screen = captureWindowToBitmap(window)

        // Scan the top-end quadrant (where Alignment.TopEnd places the ribbon) for any pixel
        // matching the active fill, 0xFFB5440E. A scan beats coordinate-cropping: it sidesteps
        // theme-dependent system-bar offsets and ribbon-positioning padding, and still rejects
        // the regression — which would leave the whole quadrant solid white.
        val xStart = screen.width / 2
        val yEnd = screen.height / 4
        var brownPixels = 0
        for (yy in 0 until yEnd) {
            for (xx in xStart until screen.width) {
                val px = screen.getPixel(xx, yy)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (r in 120..220 && g in 30..120 && b in 0..80) brownPixels++
            }
        }
        // The ribbon is 24×32dp ≈ ~3000px of fill at 4x density; demand a small minimum so a
        // stray system-theme pixel can't pass it but font-rendering AA variance can't fail it.
        assertTrue(
            "Bookmark ribbon's brown fill not visible in the top-end region " +
                "(found $brownPixels brown pixels) — the WebView is hiding the ribbon. " +
                "Verify the indicator still has Modifier.graphicsLayer in its chain.",
            brownPixels >= 50,
        )
    }

    /**
     * Capture the activity window's composited Surface into a Bitmap via the API-24+
     * `PixelCopy.request(Surface, …)` overload. Reflects on `ViewRootImpl.mSurface` to obtain
     * the Surface — internal API, but the field has existed unchanged since at least API 16.
     * Runs entirely off the UI thread; latches on the PixelCopy callback.
     */
    private fun captureWindowToBitmap(window: Window): Bitmap {
        val decor = window.decorView
        val viewRoot = decor.parent
        val surfaceField = viewRoot.javaClass.getDeclaredField("mSurface").apply { isAccessible = true }
        val surface = surfaceField.get(viewRoot) as Surface
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val result = intArrayOf(-1)
        // Run the listener on a worker thread; PixelCopy itself dispatches off the UI thread.
        val handlerThread = android.os.HandlerThread("PixelCopy").apply { start() }
        try {
            val handler = Handler(handlerThread.looper)
            PixelCopy.request(
                surface,
                bitmap,
                { code ->
                    result[0] = code
                    latch.countDown()
                },
                handler,
            )
            assertTrue("PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy result was not SUCCESS", PixelCopy.SUCCESS, result[0])
        } finally {
            handlerThread.quitSafely()
        }
        return bitmap
    }
}
