package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.toAbsoluteUrl

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

    private var publication: Publication? = null

    /** Must be called before [loadChapter] so [shouldInterceptRequest] can serve EPUB resources. */
    fun setPublication(pub: Publication) {
        publication = pub
    }

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

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // Readium serves EPUB content via https://readium_package/ — a custom domain
                // backed by shouldInterceptRequest in EpubNavigatorFragment's own WebViewClient.
                // Self-managed ChapterWebViews don't have that interceptor, so we replicate it
                // here by serving resources directly from the Publication container.
                if (uri.host != "readium_package") return super.shouldInterceptRequest(view, request)
                val pub = this@ChapterWebView.publication
                    ?: return super.shouldInterceptRequest(view, request)
                val absoluteUrl = uri.toAbsoluteUrl()
                    ?: return super.shouldInterceptRequest(view, request)
                // shouldInterceptRequest is called on a background thread; runBlocking is safe here.
                val bytes = runBlocking { pub.get(absoluteUrl)?.read()?.getOrNull() }
                    ?: return super.shouldInterceptRequest(view, request)
                val path = uri.path.orEmpty()
                val mimeType = mimeTypeForPath(path)
                val encoding = if (mimeType.startsWith("text/") || mimeType.contains("xml") || mimeType.contains("javascript")) "utf-8" else null
                return WebResourceResponse(mimeType, encoding, bytes.inputStream())
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

private fun mimeTypeForPath(path: String): String = when {
    path.endsWith(".xhtml") -> "application/xhtml+xml"
    path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".png") -> "image/png"
    path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
    path.endsWith(".gif") -> "image/gif"
    path.endsWith(".svg") -> "image/svg+xml"
    path.endsWith(".woff") -> "font/woff"
    path.endsWith(".woff2") -> "font/woff2"
    path.endsWith(".ttf") -> "font/ttf"
    path.endsWith(".otf") -> "font/otf"
    else -> "application/octet-stream"
}
