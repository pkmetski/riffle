package com.riffle.app.feature.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Shared real-WebView harness for the injected-reader-script tests (see [ReaderWebViewScriptsTest]
 * and [TypographyOverrideWebViewTest]): load a fixed HTML fixture, run synchronous evaluateJavascript
 * against it, then tear the WebView down. Kept in one place so timeout/setting tweaks don't drift.
 */
internal fun withWebViewFixture(html: String, block: (WebView) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val ready = CountDownLatch(1)
    val webViewHolder = arrayOfNulls<WebView>(1)
    instrumentation.runOnMainSync {
        val webView = WebView(context).also {
            it.settings.javaScriptEnabled = true
            it.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready.countDown()
                }
            }
        }
        webViewHolder[0] = webView
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
    assertTrue("Fixture page did not finish loading", ready.await(10, TimeUnit.SECONDS))
    val webView = webViewHolder[0]!!
    try {
        block(webView)
    } finally {
        instrumentation.runOnMainSync { webView.destroy() }
    }
}

/** Runs [script] on the WebView's main thread and blocks until its result is available. */
internal fun WebView.evalSync(script: String): String {
    val latch = CountDownLatch(1)
    val result = arrayOfNulls<String>(1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        evaluateJavascript(script) { value ->
            result[0] = value
            latch.countDown()
        }
    }
    assertTrue("evaluateJavascript timed out for: $script", latch.await(5, TimeUnit.SECONDS))
    val value = result[0]
    assertNotNull("evaluateJavascript returned null for: $script", value)
    return value!!
}
