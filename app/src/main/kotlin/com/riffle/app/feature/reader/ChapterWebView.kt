package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A [WebView] that:
 * - Has internal scrolling disabled so the parent [ContinuousReaderView] owns all scroll.
 * - Measures its content height via a [JavascriptInterface] callback after fonts load.
 * - Injects CSS variables and the TypographyOverride stylesheet on page load.
 *
 * Set [onHeightMeasured] before calling [loadChapter]. Height arrives asynchronously on
 * the main thread after [ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS] fires.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ChapterWebView(context: Context) : WebView(context) {

    /** Called on the main thread once the content height is known. */
    var onHeightMeasured: ((heightPx: Int) -> Unit)? = null

    /** Called on the main thread once the page finishes loading (before height is known). */
    var onPageFinished: (() -> Unit)? = null

    /** The chapter href this view is currently loading (e.g. `"EPUB/chapter01.xhtml"`). */
    var chapterHref: String = ""
        private set

    init {
        isScrollContainer = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        addJavascriptInterface(HeightBridge(), "RiffleChapter")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                this@ChapterWebView.onPageFinished?.invoke()
            }
        }
    }

    /**
     * Load [chapterUrl] (absolute URL from Readium's HTTP server) for chapter at [href].
     */
    fun loadChapter(href: String, chapterUrl: String) {
        chapterHref = href
        loadUrl(chapterUrl)
    }

    /**
     * Inject style variables + TypographyOverride + trigger height measurement.
     * Call this from [onPageFinished] after Readium's server has served the page.
     *
     * @param variableJs output of [ContinuousStyleInjector.buildVariableInjectionJs]
     */
    fun injectStylesAndMeasure(variableJs: String) {
        evaluateJavascript(variableJs, null)
        evaluateJavascript(typographyOverrideInjectionJs(), null)
        evaluateJavascript(ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS, null)
    }

    /** Re-measure after a preference change. */
    fun reinjectAndRemeasure(variableJs: String) = injectStylesAndMeasure(variableJs)

    private inner class HeightBridge {
        @JavascriptInterface
        fun onHeightMeasured(height: Int) {
            post { this@ChapterWebView.onHeightMeasured?.invoke(height) }
        }
    }
}
