package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.riffle.core.domain.FormattingPreferences
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

    /**
     * Formatting preferences used to inject CSS at request-intercept time (background thread).
     * @Volatile ensures the main-thread write in [loadChapter] is visible to the WebCore thread
     * that calls [shouldInterceptRequest] immediately after [loadUrl].
     */
    @Volatile
    private var currentPrefs: FormattingPreferences = FormattingPreferences()

    /** Must be called before [loadChapter] so [shouldInterceptRequest] can serve EPUB resources. */
    fun setPublication(pub: Publication) {
        publication = pub
    }

    init {
        isScrollContainer = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // Disable WebView's own over-scroll rubber-band so it doesn't compete with the parent
        // ContinuousReaderView (NestedScrollView) for scroll ownership at the edges.
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        addJavascriptInterface(HeightBridge(), "RiffleChapter")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                this@ChapterWebView.onPageFinished?.invoke()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                // All resources we serve use the readium_package virtual host so the WebView
                // treats them as same-origin with the EPUB chapter content.
                if (uri.host != "readium_package") return super.shouldInterceptRequest(view, request)

                val path = uri.path?.trimStart('/').takeIf { !it.isNullOrEmpty() }
                    ?: return super.shouldInterceptRequest(view, request)

                // Readium assets served from the merged Android assets so Continuous-mode chapters
                // use the exact same stylesheets and fonts as the Readium navigator:
                //   readium/readium-css/<file>  → assets/readium/readium-css/<file>
                //   readium/fonts/<file>        → assets/fonts/<file>  (app's own bundled fonts)
                // Both are referenced from injected <link>/@font-face at the readium_package origin,
                // so the WebView loads them same-origin with the EPUB content (no cross-origin block).
                if (path.startsWith("readium/readium-css/")) {
                    val assetName = "readium/readium-css/${path.removePrefix("readium/readium-css/")}"
                    return try {
                        WebResourceResponse(mimeTypeForPath(path), null, context.assets.open(assetName))
                    } catch (_: Exception) {
                        super.shouldInterceptRequest(view, request)
                    }
                }
                if (path.startsWith("readium/fonts/")) {
                    val assetName = "fonts/${path.removePrefix("readium/fonts/")}"
                    val mimeType = mimeTypeForPath(path)
                    return try {
                        WebResourceResponse(mimeType, null, context.assets.open(assetName))
                    } catch (_: Exception) {
                        super.shouldInterceptRequest(view, request)
                    }
                }

                val pub = this@ChapterWebView.publication
                    ?: return super.shouldInterceptRequest(view, request)
                val relUrl = Url(path)
                    ?: return super.shouldInterceptRequest(view, request)
                // shouldInterceptRequest is called on a background thread; runBlocking is safe here.
                val bytes = runBlocking { pub.get(relUrl)?.read()?.getOrNull() }
                    ?: return super.shouldInterceptRequest(view, request)
                val mimeType = mimeTypeForPath(path)
                val encoding = if (mimeType.startsWith("text/") || mimeType.contains("xml") || mimeType.contains("javascript")) "utf-8" else null

                // For HTML/XHTML chapter resources, inject the same ReadiumCSS stylesheets + the
                // --USER__ settings attribute Readium injects, so the first paint is already styled
                // (no FOUC) AND renders identically to Scroll/Paginated mode.
                val finalBytes = if (mimeType == "text/html" || mimeType == "application/xhtml+xml") {
                    ContinuousStyleInjector.injectInto(String(bytes, Charsets.UTF_8), currentPrefs)
                        .toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
                return WebResourceResponse(mimeType, encoding, finalBytes.inputStream())
            }
        }
    }

    /**
     * Load [chapterUrl] for chapter at [href], styled with [prefs].
     * [prefs] is stored before [loadUrl] so [shouldInterceptRequest] (which runs on the WebCore
     * thread immediately after) can inject the CSS directly into the HTML bytes, eliminating the
     * flash of unstyled content that occurs when styles are injected via JS after page load.
     */
    fun loadChapter(href: String, chapterUrl: String, prefs: FormattingPreferences) {
        chapterHref = href
        currentPrefs = prefs
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
