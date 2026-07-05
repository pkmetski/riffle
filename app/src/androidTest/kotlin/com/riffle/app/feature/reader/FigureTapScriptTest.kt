package com.riffle.app.feature.reader

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for [FigureTapScript]. Runs the actual JS against a bare WebView loaded
 * with a small HTML fixture that mimics the shapes we care about (bare `<img>`, single-image
 * `<figure>`, anchor-wrapped `<img>`, inline `<svg>`, `<picture>`), then dispatches JavaScript
 * clicks on each and asserts the resulting bridge payload matches the expectation.
 *
 * Why an instrumentation test, not a JVM one: the hit-test logic IS the JavaScript, and there's no
 * good way to run it deterministically without a real WebView. The JS payload builder — the piece
 * a schema drift would break — is what this test pins.
 *
 * Why not a full reader-open harness: the reader stack is a lot to spin up just to trigger a click
 * on a figure. This narrower test covers the tap-router (findFigure + payloadFor) exhaustively,
 * and [FigureZoomTest] JVM-tests the [FigureTapMessageParser] end that turns the payload into a
 * [FigureZoomState]. Together they cover the round trip that the design specifies.
 */
@RunWith(AndroidJUnit4::class)
class FigureTapScriptTest {

    private class Recorder {
        val latch = CountDownLatch(1)
        @Volatile var payload: String? = null

        @JavascriptInterface
        fun onFigureTap(json: String) {
            payload = json
            latch.countDown()
        }
    }

    private val fixture = """
        <!doctype html>
        <html><body style="margin:0">
          <img id="bare" src="images/bare.jpg" width="80" height="60"
               style="position:absolute;left:0;top:0;width:80px;height:60px">
          <figure id="wrapfig" style="position:absolute;left:100px;top:0;margin:0">
            <img src="images/wrapped.png" width="80" height="60"
                 style="width:80px;height:60px"><figcaption>x</figcaption>
          </figure>
          <a id="linked" href="chap02.xhtml#note" style="position:absolute;left:200px;top:0">
            <img src="images/linked.png" width="80" height="60"
                 style="width:80px;height:60px">
          </a>
          <svg id="inlinesvg" width="80" height="60" viewBox="0 0 80 60"
               style="position:absolute;left:300px;top:0;width:80px;height:60px">
            <rect width="80" height="60"/>
          </svg>
          <picture id="pic" style="position:absolute;left:400px;top:0">
            <img src="images/pic.jpg" width="80" height="60"
                 style="width:80px;height:60px">
          </picture>
        </body></html>
    """.trimIndent()

    private fun runTapCase(elementId: String): String? {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recorder = Recorder()
        val pageLoaded = CountDownLatch(1)
        val webView = arrayOfNulls<WebView>(1)
        instrumentation.runOnMainSync {
            @Suppress("SetJavaScriptEnabled")
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(recorder, FigureTapScript.PAGED_BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded.countDown()
                    }
                }
                loadDataWithBaseURL(null, fixture, "text/html", "utf-8", null)
            }
            webView[0] = wv
        }
        assertTrue(pageLoaded.await(10, TimeUnit.SECONDS))
        // Install the script and, in the SAME JS turn, dispatch a bubbling capture-phase click on
        // the element. Element.click() on API-25 Chromium (Chrome 55) doesn't reliably drive the
        // capture-phase document listener the install script uses, and it doesn't dispatch at all
        // for <svg> / <picture>; a synthetic MouseEvent dispatched with bubbles:true works on
        // every WebView version. Chaining install+dispatch in one evaluateJavascript payload also
        // removes the install-vs-dispatch race that a separate sleep tried to paper over.
        val installed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView[0]!!.evaluateJavascript(
                FigureTapScript.installScript(FigureTapScript.PAGED_BRIDGE_NAME) +
                    ";(function(){var el=document.getElementById('$elementId');" +
                    "var ev=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});" +
                    "el.dispatchEvent(ev);})();",
            ) { _ -> installed.countDown() }
        }
        assertTrue(installed.await(5, TimeUnit.SECONDS))
        // 1s bounded wait for the bridge callback. Anchor-wrapped case never fires, so callers
        // for that case just consume the timeout and inspect payload (null = no fire).
        recorder.latch.await(1, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            webView[0]!!.destroy()
        }
        return recorder.payload
    }

    @Test
    fun bareImage_firesTapWithSrcAndDimensions() {
        val payload = runTapCase("bare") ?: error("No payload for bare <img>")
        val obj = org.json.JSONObject(payload)
        assertEquals("img", obj.getString("kind"))
        assertTrue(obj.getString("href").endsWith("images/bare.jpg"))
        assertEquals(80, obj.getInt("w"))
        assertEquals(60, obj.getInt("h"))
    }

    @Test
    fun figureWrappedImage_firesTapForContainedImage() {
        val payload = runTapCase("wrapfig") ?: error("No payload for wrapped <figure>")
        val obj = org.json.JSONObject(payload)
        assertEquals("img", obj.getString("kind"))
        assertTrue(obj.getString("href").endsWith("images/wrapped.png"))
    }

    @Test
    fun anchorWrappedImage_doesNotFireTap() {
        // Regression: footnote / cross-reference image-as-link must remain a link, not open the
        // zoom viewer. FigureTapScript.findFigure returns null when it walks through an <a href>.
        val payload = runTapCase("linked")
        assertNull(payload)
    }

    @Test
    fun inlineSvg_firesTapWithOuterHtml() {
        val payload = runTapCase("inlinesvg") ?: error("No payload for inline <svg>")
        val obj = org.json.JSONObject(payload)
        assertEquals("svg", obj.getString("kind"))
        val svg = obj.getString("svg")
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("<rect"))
    }

    @Test
    fun picture_firesTapWithInnerImgSrc() {
        val payload = runTapCase("pic") ?: error("No payload for <picture>")
        val obj = org.json.JSONObject(payload)
        assertEquals("img", obj.getString("kind"))
        assertTrue(obj.getString("href").endsWith("images/pic.jpg"))
    }
}
