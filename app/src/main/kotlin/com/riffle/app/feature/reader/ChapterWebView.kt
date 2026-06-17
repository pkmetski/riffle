package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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

    /**
     * Called on the main thread when this WebView's renderer process is gone (reclaimed by the
     * system under memory pressure, or crashed). The dead WebView renders blank from here on, so
     * the parent must rebuild it. If this event is not consumed the platform's default behaviour is
     * to crash the whole app.
     */
    var onRenderGone: (() -> Unit)? = null

    /**
     * Called on the main thread when the user taps an in-book link (footnote / cross-reference).
     * [href] is the target EPUB resource path with any `#fragment`. The WebView does NOT follow the
     * link itself (that would replace this chapter's content); the parent navigates instead.
     */
    var onInternalLink: ((href: String) -> Unit)? = null

    /** Called on the main thread when the user taps an external (http/https) link. */
    var onExternalLink: ((url: String) -> Unit)? = null

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

    /**
     * Incremented on every [loadChapter]. Each loaded page stamps this value into
     * `window.__riffleToken` (see [injectStylesAndMeasure]) and echoes it back with every height
     * report. [HeightBridge] drops reports whose token != the current one, so a recycled WebView's
     * *previous* chapter — whose ResizeObserver / delayed re-measure timers may still fire for a
     * moment after the view is reused — can't deliver a stale (typically much taller) height to the
     * new chapter and leave it over-sized (a large white gap below the content).
     */
    private var loadToken = 0

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

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Not called for the initial loadChapter() load, only for navigations the page
                // initiates — i.e. a tapped link. Intercept so the link doesn't replace this
                // chapter's content; route in-book links to the parent (which navigates the reader
                // with a return card) and external links out to a browser.
                val url = request.url.toString()
                val internal = ContinuousPositionTracker.internalLinkHref(url)
                return if (internal != null) {
                    onInternalLink?.invoke(internal)
                    true
                } else {
                    onExternalLink?.invoke(url)
                    true
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The renderer process backing this WebView is gone — typically reclaimed by the
                // system under memory pressure (large chapters held in several stacked WebViews make
                // this likelier). Returning true tells the platform we've handled it, so the app is
                // NOT killed; the parent rebuilds the (now-dead, permanently blank) WebView.
                this@ChapterWebView.onRenderGone?.invoke()
                return true
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
                val rawMime = mimeTypeForPath(path)
                val isHtml = rawMime == "text/html" || rawMime == "application/xhtml+xml"
                // EPUB content documents are XHTML even when their file extension is .htm/.html.
                // Serving them as text/html makes the WebView use the lenient HTML parser, which
                // ignores XML self-closing syntax on non-void elements — e.g. `<h1 class="chapter"/>`
                // with no `</h1>` is treated as an *open* heading that then wraps the entire chapter
                // body, so every paragraph inherits the heading's `text-align: center`. Serving as
                // application/xhtml+xml (as Readium's own navigator does) selects the XHTML parser,
                // which honours the self-close, leaving the heading empty and the body correctly
                // aligned. EPUB requires well-formed XHTML, so strict parsing is safe here.
                val mimeType = if (isHtml) "application/xhtml+xml" else rawMime
                val encoding = if (mimeType.startsWith("text/") || mimeType.contains("xml") || mimeType.contains("javascript")) "utf-8" else null

                // For HTML/XHTML chapter resources, inject the same ReadiumCSS stylesheets + the
                // --USER__ settings attribute Readium injects, so the first paint is already styled
                // (no FOUC) AND renders identically to Scroll/Paginated mode.
                val finalBytes = if (isHtml) {
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
        loadToken++
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
        // Stamp this page with the current load token BEFORE wiring measurement, so every height
        // report (including late ResizeObserver / timeout fires) carries it and the bridge can
        // reject reports from a recycled WebView's previous page.
        evaluateJavascript("window.__riffleToken=$loadToken;", null)
        evaluateJavascript(ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS, null)
        evaluateJavascript(ContinuousStyleInjector.TAP_LISTENER_JS, null)
    }

    /** Re-inject user styles and re-measure after a preference change. */
    fun reinjectAndRemeasure(styleJs: String) = injectStylesAndMeasure(styleJs)

    private inner class HeightBridge {
        @JavascriptInterface
        fun onHeightMeasured(height: Int, token: Int) {
            // Drop reports from a previous chapter still settling in this (recycled) WebView.
            post { if (token == loadToken) this@ChapterWebView.onHeightMeasured?.invoke(height) }
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
