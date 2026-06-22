package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS] returns the real content height
 * for a chapter that is shorter than the WebView's viewport — not the viewport height.
 *
 * Background: [ContinuousStyleInjector] forces `overflow: visible !important` on the `<html>`
 * element. In Chrome's rendering model this makes `<body>` the effective scroll container, causing
 * `body.scrollHeight` to be clamped to `max(content, viewport)`. A short chapter (e.g. a Dedication
 * page with only a heading) would therefore report the full viewport height as its height, exactly
 * matching the placeholder height — so the reader can never distinguish "not yet measured" from
 * "already measured at real height". The initial scroll fires with placeholder-based coordinates;
 * the viewport midpoint ends up in the *next* chapter; a spurious forward shift fires; the user
 * cannot progress past that chapter.
 *
 * The fix removes `b.scrollHeight` from the [HEIGHT_MEASUREMENT_JS] `Math.max()`. These tests
 * assert both the post-fix behaviour (short chapter → reports real height) and the regression
 * guard (reported height must be << viewport height).
 */
@RunWith(AndroidJUnit4::class)
class HeightMeasurementJsTest {

    /**
     * HTML that mimics a Dedication-style short chapter: ReadiumCSS scroll-mode class on
     * `<html>` (`overflow: visible; height: auto; min-height: 0`) and a body with ~120 CSS px
     * of content. The style attribute replicates what ContinuousStyleInjector produces.
     */
    private val shortChapterHtml = """
        <!DOCTYPE html>
        <html style="overflow: visible !important; height: auto !important; min-height: 0 !important;"
              lang="en">
          <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin: 20px; overflow: visible;">
            <h1 style="font-size: 24px; margin: 0 0 20px 0;">DEDICATION</h1>
            <p style="font-size: 16px; line-height: 1.5; margin: 0;">
              To our colleagues, who taught us the essence of mutual influence.
            </p>
          </body>
        </html>
    """.trimIndent()

    private val tallChapterHtml = """
        <!DOCTYPE html>
        <html style="overflow: visible !important; height: auto !important; min-height: 0 !important;"
              lang="en">
          <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin: 20px; height: 4000px; overflow: visible;">
            <p>Long chapter content that exceeds the viewport height.</p>
          </body>
        </html>
    """.trimIndent()

    /**
     * The placeholder height used by ContinuousReaderView for unmeasured chapters is
     * `resources.displayMetrics.heightPixels`. We use 2276 here — a common value on a
     * 5.5" FHD phone — but the key invariant is that a short chapter measures MUCH LESS
     * than the viewport height (not that it measures a specific number of pixels).
     */
    private val viewportHeight = 2276
    private val viewportWidth = 1080

    @Test
    fun shortChapterReportsRealHeightNotViewportHeight() {
        withSizedWebViewFixture(shortChapterHtml, viewportWidth, viewportHeight) { webView ->
            webView.awaitInnerHeight()

            // Wire a minimal RiffleChapter bridge so the JS can call onHeightMeasured.
            webView.evalSync("""
                window.RiffleChapter = {
                    onHeightMeasured: function(h, t) { window.__testMeasuredHeight = h; }
                };
                window.__riffleToken = 0;
            """.trimIndent())

            webView.evalSync(ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS)

            // Wait up to 1 s for the height to be reported (the IIFE calls report() immediately,
            // but we allow a brief settling window for layout completion on slower devices).
            val deadline = System.currentTimeMillis() + 1_000
            var measuredPx = 0
            while (System.currentTimeMillis() < deadline && measuredPx <= 0) {
                measuredPx = webView.evalSync("window.__testMeasuredHeight || 0")
                    .trim('"').toDoubleOrNull()?.toInt() ?: 0
                if (measuredPx <= 0) Thread.sleep(40)
            }

            assertTrue(
                "HEIGHT_MEASUREMENT_JS must report a positive height for a short chapter " +
                    "(measured=$measuredPx)",
                measuredPx > 0,
            )
            // The content is ~120 CSS px; the viewport is $viewportHeight device px.
            // The measured height (device px) must be substantially less than the viewport —
            // if body.scrollHeight clamping were active it would equal the viewport height exactly.
            assertTrue(
                "Short chapter must NOT report viewport-clamped height " +
                    "(measured=$measuredPx, viewport=$viewportHeight); " +
                    "body.scrollHeight viewport-clamping is the regression being guarded",
                measuredPx < viewportHeight / 2,
            )
        }
    }

    @Test
    fun tallChapterReportsHeightLargerThanViewport() {
        withSizedWebViewFixture(tallChapterHtml, viewportWidth, viewportHeight) { webView ->
            webView.awaitInnerHeight()

            webView.evalSync("""
                window.RiffleChapter = {
                    onHeightMeasured: function(h, t) { window.__testMeasuredHeight = h; }
                };
                window.__riffleToken = 0;
            """.trimIndent())

            webView.evalSync(ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS)

            val deadline = System.currentTimeMillis() + 1_000
            var measuredPx = 0
            while (System.currentTimeMillis() < deadline && measuredPx <= 0) {
                measuredPx = webView.evalSync("window.__testMeasuredHeight || 0")
                    .trim('"').toDoubleOrNull()?.toInt() ?: 0
                if (measuredPx <= 0) Thread.sleep(40)
            }

            assertTrue(
                "Tall chapter (4000 CSS px body) must measure taller than the viewport " +
                    "(measured=$measuredPx, viewport=$viewportHeight)",
                measuredPx > viewportHeight,
            )
        }
    }
}
