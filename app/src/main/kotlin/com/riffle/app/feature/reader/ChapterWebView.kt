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
import org.readium.r2.shared.util.Url

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

    /**
     * Called on the main thread when the user taps the chapter (no scroll movement).
     * Wire this to the reader's chrome toggle so taps in Continuous mode show/hide the
     * top/bottom bars, matching the behaviour of the standard Readium navigator.
     */
    var onTap: (() -> Unit)? = null

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
                // Publication.get() resolves against the publication container using paths relative
                // to the publication root (e.g. "text/part0022_split_000.html"). Passing the full
                // https://readium_package/... absolute URL doesn't work because pub.get() doesn't
                // know the readium_package virtual hostname; it just understands relative paths.
                val path = uri.path?.trimStart('/').takeIf { !it.isNullOrEmpty() }
                    ?: return super.shouldInterceptRequest(view, request)
                val relUrl = Url(path)
                    ?: return super.shouldInterceptRequest(view, request)
                // shouldInterceptRequest is called on a background thread; runBlocking is safe here.
                val bytes = runBlocking { pub.get(relUrl)?.read()?.getOrNull() }
                    ?: return super.shouldInterceptRequest(view, request)
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
     * Inject user styles + trigger height measurement.
     * Call this from [onPageFinished] after the page has loaded.
     *
     * @param styleJs output of [ContinuousStyleInjector.buildStyleInjectionJs]
     */
    fun injectStylesAndMeasure(styleJs: String) {
        evaluateJavascript(styleJs, null)
        evaluateJavascript(ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS, null)
        evaluateJavascript(ContinuousStyleInjector.TAP_LISTENER_JS, null)
    }

    /** Re-inject user styles and re-measure after a preference change. */
    fun reinjectAndRemeasure(styleJs: String) = injectStylesAndMeasure(styleJs)

    private inner class HeightBridge {
        @JavascriptInterface
        fun onHeightMeasured(height: Int) {
            post { this@ChapterWebView.onHeightMeasured?.invoke(height) }
        }

        @JavascriptInterface
        fun onTap() {
            post { this@ChapterWebView.onTap?.invoke() }
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
