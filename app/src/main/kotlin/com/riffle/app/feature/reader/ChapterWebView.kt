package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.NoopLogger
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
 * the main thread after [ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS] fires.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ChapterWebView(context: Context) : WebView(context), ChapterWebViewLike {

    private companion object {
        // Text-selection menu item ids (see wrapSelectionCallback).
        const val MENU_COPY = android.view.Menu.FIRST
        const val MENU_HIGHLIGHT = android.view.Menu.FIRST + 1
        const val MENU_PLAY = android.view.Menu.FIRST + 2
        const val MENU_SEARCH = android.view.Menu.FIRST + 3
        const val MENU_SHARE = android.view.Menu.FIRST + 4
    }

    /** Called on the main thread once the content height is known. */
    var onHeightMeasured: ((heightPx: Int) -> Unit)? = null

    /** Called on the main thread once the page finishes loading (before height is known). */
    var onPageFinished: (() -> Unit)? = null

    /**
     * Called on the main thread when the user taps the chapter (no scroll movement).
     * Wire this to the reader's chrome toggle so taps in Continuous mode show/hide the
     * top/bottom bars, matching the behaviour of the standard Readium navigator.
     */
    override var onTap: (() -> Unit)? = null

    /**
     * Called on the main thread when this WebView's renderer process is gone (reclaimed by the
     * system under memory pressure, or crashed). The dead WebView renders blank from here on, so
     * the parent must rebuild it. If this event is not consumed the platform's default behaviour is
     * to crash the whole app.
     */
    override var onRenderGone: (() -> Unit)? = null

    /**
     * Called on the main thread when the user taps an in-book link (footnote / cross-reference).
     * [href] is the target EPUB resource path with any `#fragment`. The WebView does NOT follow the
     * link itself (that would replace this chapter's content); the parent navigates instead.
     */
    override var onInternalLink: ((href: String) -> Unit)? = null

    /** Called on the main thread when the user taps an external (http/https) link. */
    override var onExternalLink: ((url: String) -> Unit)? = null

    /**
     * Called on the main thread with the resolved footnote body when the user taps a same-document
     * footnote anchor. The host shows the footnote popup. Continuous mode resolves the note from the
     * chapter's own HTML (see [rawChapterHtml]) using [FootnoteResolver].
     */
    override var onFootnoteContent: ((FootnoteContent) -> Unit)? = null

    /**
     * Called on the main thread when the user taps a same-document anchor that is NOT a footnote
     * (e.g. a "Figure 3.1" cross-reference). [fragmentId] is the target id, without the leading '#'.
     * The host scrolls the outer viewport to that element inside this chapter's WebView and shows
     * the return-to-position card so the user can hop back.
     *
     * Distinct from [onInternalLink] which fires only on cross-resource links routed through
     * `shouldOverrideUrlLoading` — same-document `#id` clicks never reach that path.
     */
    override var onCrossReferenceTap: ((fragmentId: String) -> Unit)? = null

    /**
     * Called on the main thread when the user taps a figure (`<img>`, inline `<svg>`, `<picture>`,
     * or single-image `<figure>`) that is NOT wrapped in a link. [payload] is the JSON emitted by
     * [FigureTapScript]; the host parses it via [FigureTapMessageParser].
     */
    override var onFigureTap: ((payload: String) -> Unit)? = null

    /**
     * Called on the main thread when the user long-presses a figure. [payload] is already parsed
     * (unlike [onFigureTap], which forwards the raw JSON) since [FigureLongPressMessageParser] has
     * no dependency on WebView/Android types and can run directly on the JS binder thread.
     */
    override var onFigureLongPress: ((payload: FigureLongPressPayload) -> Unit)? = null

    /**
     * The raw HTML of the chapter document currently loaded, retained so a footnote tap can resolve
     * the note body without a re-fetch. Set the first time an HTML resource is served for a load (the
     * main document is fetched first), cleared on each [loadChapter]. The parsed form is cached lazily
     * in [footnoteDoc].
     */
    @Volatile
    private var rawChapterHtml: String? = null
    // @Volatile: reset on the main thread in loadChapter() but read + lazily cached on the WebView
    // JS binder thread in onFootnoteAnchorTap(); without it the binder thread could miss the reset
    // after a recycle and resolve a footnote against the previous chapter's parsed document.
    @Volatile
    private var footnoteDoc: org.jsoup.nodes.Document? = null

    /**
     * Called on the main thread when a text-selection action mode opens (true) or closes (false).
     * The parent [ContinuousReaderView] uses this to suppress its early scroll intercept while a
     * selection handle is being dragged — otherwise its half-touch-slop intercept (see
     * `onInterceptTouchEvent`) steals the gesture the moment the user drags vertically, scrolling
     * the page mid-selection and breaking the highlight.
     */
    override var onSelectionActiveChanged: ((active: Boolean) -> Unit)? = null

    /** When true, the text-selection menu offers "Highlight" (books with annotations UI). */
    override var annotationsAvailable: Boolean = false

    /**
     * Called on the main thread with the selected text, its within-chapter progression (0..1),
     * its bounding rect in device pixels relative to this WebView's top-left corner, and the
     * `before` / `after` document-text context windows used to disambiguate which occurrence
     * the user picked when the selected text repeats in the chapter.
     */
    override var onHighlight: ((selectedText: String, progression: Double, rect: android.graphics.Rect, before: String, after: String) -> Unit)? = null

    /**
     * Called on the main thread when the user taps an injected annotation mark (`<mark
     * data-riffle-ann>`). [rect] is in device pixels relative to this WebView's top-left corner.
     */
    override var onAnnotationTap: ((id: String, rect: android.graphics.Rect) -> Unit)? = null

    /**
     * Called on the main thread when the user taps the note glyph (`<span data-riffle-note-glyph>`)
     * next to an annotation that has a note. [rect] is in device pixels relative to this WebView.
     */
    override var onAnnotationNoteTap: ((id: String, rect: android.graphics.Rect) -> Unit)? = null

    /** When true, the text-selection menu offers "Play" (readaloud books only). */
    override var readaloudAvailable: Boolean = false

    /**
     * Called on the main thread when the user taps "Play". Receives the selected text and this
     * WebView's [evaluateJavascript] so the host can run geometry-based sentence resolution.
     */
    var onPlayFromHere: ((selectedText: String, evalJs: (String, (String?) -> Unit) -> Unit) -> Unit)? = null

    /** The chapter href this view is currently loading (e.g. `"EPUB/chapter01.xhtml"`). */
    override var chapterHref: String = ""
        private set

    /**
     * Explicit override to disambiguate [ChapterWebViewLike.evaluateJavascript]'s Kotlin-lambda
     * signature from [WebView]'s own `evaluateJavascript(String, ValueCallback<String>?)` — without
     * this, every in-class call site becomes an overload-resolution-ambiguity compile error.
     */
    override fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?) {
        super.evaluateJavascript(script, resultCallback)
    }

    /** Fire-and-forget JS eval. Centralises the Kotlin-lambda-overload disambiguation cast so
     *  in-class callers read as a plain call instead of repeating `null as ((String?) -> Unit)?`. */
    private fun evalJs(script: String) {
        evaluateJavascript(script, null as ((String?) -> Unit)?)
    }

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

    /**
     * Optional sink for JS console messages emitted by this chapter's page. Set by
     * [ContinuousWindowController] to the ReaderDecoration [Logger] so the in-app debug screen
     * shows DOM-side errors (e.g. #428's `createTreeWalker` throw) alongside the Kotlin-side
     * decoration events. WARNING/ERROR forward to `logger.w`/`logger.e`; DEBUG/LOG/TIP are dropped
     * to keep noise low.
     */
    var jsConsoleLogger: Logger = NoopLogger

    init {
        isScrollContainer = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // Disable WebView's own over-scroll rubber-band so it doesn't compete with the parent
        // ContinuousReaderView (NestedScrollView) for scroll ownership at the edges.
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        // Explicitly override OEM defaults that differ from AOSP and break text sizing:
        //
        // useWideViewPort: some manufacturers default this to true, which makes a chapter without
        // a <meta name="viewport"> use a 980px layout viewport. With loadWithOverviewMode also
        // defaulting to true on those devices the page is then zoomed to ~42% of normal size,
        // making text appear far smaller in Continuous mode than in Scroll/Paginated mode.
        // Forcing false pins the layout viewport to the WebView's CSS-pixel width regardless of
        // any viewport meta in the EPUB HTML, which is always correct for reflowable content.
        //
        // textZoom: some OEM WebViews automatically apply the system font-scale to textZoom
        // (making it < 100 when the user picks "Small" in Accessibility → Font Size). Readium
        // overrides textZoom explicitly to implement its own font-size preference, so its text
        // is unaffected. We control font size via --USER__fontSize CSS; pinning textZoom to 100
        // keeps Continuous mode visually identical to Scroll/Paginated mode at the same setting.
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.textZoom = 100
        // Force each chapter WebView to keep its full content rasterized even when part of it is
        // off-screen. In continuous mode we stack many WebViews inside a NestedScrollView, so any
        // given WebView is typically only partially visible; Chromium's default tile pipeline can
        // then drop or lazily re-raster the off-screen portion, producing brief blank flashes at
        // chapter boundaries as the reader scrolls back and forth across them. OFF_SCREEN_PRERASTER
        // is Android's supported knob for this exact case.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.OFF_SCREEN_PRERASTER,
            )
        ) {
            androidx.webkit.WebSettingsCompat.setOffscreenPreRaster(settings, true)
        }
        addJavascriptInterface(HeightBridge(), FigureTapScript.CONTINUOUS_BRIDGE_NAME)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val m = consoleMessage ?: return false
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR ->
                        jsConsoleLogger.e(LogChannel.ReaderDecoration) { formatConsoleLine(m) }
                    ConsoleMessage.MessageLevel.WARNING ->
                        jsConsoleLogger.w(LogChannel.ReaderDecoration) { formatConsoleLine(m) }
                    else -> Unit
                }
                return false
            }

            private fun formatConsoleLine(m: ConsoleMessage): String =
                "JS ${m.sourceId()?.substringAfterLast('/') ?: "?"}:${m.lineNumber()} ${m.message()}"
        }
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
                // Highlights-mode accent-bar tap: the synthesised HTML's tap span navigates here.
                // Route the annotation id + tap rect back to the highlight-actions popup and
                // swallow the navigation so the chapter content stays put. The URL carries the
                // tap element's bounding rect in CSS pixels (see HighlightsPublicationFactory);
                // convert to device px so the downstream binder (ChapterWebViewBinder.onAnnotationTap)
                // sees the same shape it would from the JS bridge's HeightBridge.onAnnotationTap.
                val parts = com.riffle.app.feature.reader.highlights.parseAnnotationTapUrlParts(url)
                if (parts != null) {
                    val dpr = resources.displayMetrics.density
                    val rect = if (parts.hasRect()) {
                        android.graphics.Rect(
                            (parts.cssLeft!! * dpr).toInt(),
                            (parts.cssTop!! * dpr).toInt(),
                            (parts.cssRight!! * dpr).toInt(),
                            (parts.cssBottom!! * dpr).toInt(),
                        )
                    } else {
                        android.graphics.Rect()
                    }
                    onAnnotationTap?.invoke(parts.annotationId, rect)
                    return true
                }
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
                    val html = String(bytes, Charsets.UTF_8)
                    // Retain the first HTML document served for this load (the main chapter, fetched
                    // before any sub-resource) so a footnote tap can resolve the note body locally.
                    if (rawChapterHtml == null) rawChapterHtml = html
                    ContinuousStyleInjector.injectInto(html, currentPrefs).toByteArray(Charsets.UTF_8)
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
        rawChapterHtml = null
        footnoteDoc = null
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
        evalJs(styleJs)
        // Stamp this page with the current load token BEFORE wiring measurement, so every height
        // report (including late ResizeObserver / timeout fires) carries it and the bridge can
        // reject reports from a recycled WebView's previous page.
        evalJs("window.__riffleToken=$loadToken;")
        evalJs(ContinuousScriptInjector.HEIGHT_MEASUREMENT_JS)
        evalJs(ContinuousScriptInjector.TAP_LISTENER_JS)
        evalJs(ContinuousScriptInjector.SAME_DOC_ANCHOR_LISTENER_JS)
        // Figure-tap hit-test. Runs in capture-phase click BEFORE the tap-listener above (which
        // toggles immersive) and the same-doc anchor listener, so figure taps stopPropagation and
        // never reach the immersive router. Uses the existing RiffleChapter object — see the
        // onFigureTap addition in HeightBridge below.
        evalJs(FigureTapScript.installScript(FigureTapScript.CONTINUOUS_BRIDGE_NAME))
    }

    /** Re-inject user styles and re-measure after a preference change. */
    fun reinjectAndRemeasure(styleJs: String) = injectStylesAndMeasure(styleJs)

    /**
     * Resolve [fragment] (an element id / name from a TOC or cross-reference link) to its top offset
     * within this chapter, in DEVICE px (so it composes with the chapter's layout height). Calls
     * [callback] with null when the element isn't found. Used to land a TOC/anchor jump on the actual
     * heading instead of the resource top.
     */
    fun anchorOffsetTopDevicePx(fragment: String, callback: (Int?) -> Unit) {
        val esc = fragment.replace("\\", "\\\\").replace("'", "\\'")
        val js = """(function(){
            var e = document.getElementById('$esc') ||
                    document.querySelector("[id='$esc'], [name='$esc'], a[name='$esc']");
            if (!e) return -1;
            var r = e.getBoundingClientRect();
            var y = r.top + (window.pageYOffset || document.documentElement.scrollTop || 0);
            return Math.max(0, Math.round(y * (window.devicePixelRatio || 1)));
        })()"""
        evaluateJavascript(js) { raw ->
            val v = raw?.trim('"')?.toIntOrNull()
            callback(if (v == null || v < 0) null else v)
        }
    }

    /**
     * Resolve the on-screen Y of the `<mark data-riffle-ann="<id>">` decoration that wraps the
     * highlight for annotation [id], in device pixels relative to this WebView's content top — so
     * it composes with [ContinuousReaderView]'s `slot.top` to form an absolute parent scrollY.
     *
     * The mark is injected by [ContinuousStyleInjector.applyAnnotationHighlightsJs], which runs
     * from [onPageFinished] AFTER the typography reflow that [injectStylesAndMeasure] triggers, so
     * when found its rect already reflects the final post-reflow layout — the exact thing a
     * slot+progression landing keeps missing.
     *
     * Returns null when the mark is not yet in the DOM (cold-start, before the annotation has
     * been observed and applied). Callers fall back to the existing anchor/progression landing;
     * the reflow-tracking re-land re-fires this query on every target remeasure so once the mark
     * appears, the landing snaps onto it.
     */
    fun annotationOffsetTopDevicePx(id: String, callback: (Int?) -> Unit) {
        val esc = id.replace("\\", "\\\\").replace("'", "\\'")
        val js = """(function(){
            var e = document.querySelector("[data-riffle-ann='$esc']");
            if (!e) return -1;
            var r = e.getBoundingClientRect();
            var y = r.top + (window.pageYOffset || document.documentElement.scrollTop || 0);
            return Math.max(0, Math.round(y * (window.devicePixelRatio || 1)));
        })()"""
        evaluateJavascript(js) { raw ->
            val v = raw?.trim('"')?.toIntOrNull()
            callback(if (v == null || v < 0) null else v)
        }
    }

    // ── Text-selection menu ──────────────────────────────────────────────────────
    // The Readium fragment provides Riffle's custom selection menu in paged/scroll mode; in
    // Continuous mode selection happens in these WebViews, so we wrap their action mode to offer the
    // same items (Copy / Search / Share, plus "Play from here" for readaloud books) instead of the
    // bare browser default. Both startActionMode overloads are wrapped to cover the floating menu.

    override fun startActionMode(callback: android.view.ActionMode.Callback): android.view.ActionMode =
        super.startActionMode(wrapSelectionCallback(callback))

    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode =
        super.startActionMode(wrapSelectionCallback(callback), type)

    private fun wrapSelectionCallback(inner: android.view.ActionMode.Callback) =
        object : android.view.ActionMode.Callback2() {
            // The floating selection toolbar anchors to the rect returned here. We MUST forward to
            // the WebView's own callback (a Callback2 that reports the selection's bounds in view
            // coordinates): in Continuous mode each ChapterWebView is sized to its whole chapter
            // (up to tens of thousands of px) and scrolled far above the screen, so the default
            // content rect — the view's full bounds — would anchor the toolbar to the top of that
            // giant rect, making the menu appear at the top of the screen instead of next to the
            // selection. Forwarding gives the framework the real selection rect to map to screen.
            override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if (inner is android.view.ActionMode.Callback2) {
                    inner.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
                // Clamp the rect to the WebView's WINDOW-visible portion (in view-local coords).
                // Each ChapterWebView is laid out as tall as its entire chapter and on edge-to-edge
                // displays its bottom can extend BELOW the app window's visible area into the
                // gesture-bar / status-bar inset strips — pixels that are visually painted but lie
                // outside `windowVisibleDisplayFrame`. If Chromium returns a selection rect inside
                // that strip, the framework's FloatingToolbar maps it to a screen y that's below the
                // viewport bottom; `availableHeightBelowContent` goes negative and the toolbar ends
                // up just off-screen below. (Using `getGlobalVisibleRect` here doesn't help because
                // it returns the WebView's bounds intersected with parent clipping but NOT clipped
                // to the window's visible display frame — so the selection is "globally visible"
                // even when it's painted under the gesture bar. Use windowVisibleDisplayFrame
                // explicitly.) Clamping keeps the rect inside the actually-visible app-window band.
                val wvdf = android.graphics.Rect()
                getWindowVisibleDisplayFrame(wvdf)
                val locWin = IntArray(2)
                getLocationInWindow(locWin)
                val clamped = clampSelectionYBandToWindow(
                    rectTop = outRect.top,
                    rectBottom = outRect.bottom,
                    viewportTop = wvdf.top,
                    viewportBottom = wvdf.bottom,
                    viewLocationInWindowY = locWin[1],
                    viewHeight = height,
                )
                outRect.top = clamped.first
                outRect.bottom = clamped.second
            }

            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                onSelectionActiveChanged?.invoke(true)
                menu.clear()
                menu.add(0, MENU_COPY, 0, android.R.string.copy)
                if (annotationsAvailable) {
                    menu.add(0, MENU_HIGHLIGHT, 1, "Highlight")
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                if (readaloudAvailable) {
                    menu.add(0, MENU_PLAY, 2, "Play")
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                menu.add(0, MENU_SEARCH, 3, "Search")
                menu.add(0, MENU_SHARE, 4, "Share")
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> withSelectionText { copyToClipboard(it) }
                    MENU_HIGHLIGHT -> withSelectionTextAndProgression { text, prog, rect, before, after -> onHighlight?.invoke(text, prog, rect, before, after) }
                    MENU_SEARCH -> withSelectionText { webSearch(it) }
                    MENU_SHARE -> withSelectionText { shareText(it) }
                    MENU_PLAY -> withSelectionText { text ->
                        onPlayFromHere?.invoke(text) { js, cb -> evaluateJavascript(js, cb) }
                    }
                    else -> return inner.onActionItemClicked(mode, item)
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                onSelectionActiveChanged?.invoke(false)
                inner.onDestroyActionMode(mode)
            }
        }

    /**
     * Read the current selection's plain text (async, on the JS thread) then run [block] with it.
     *
     * Prefers the pre-stashed selection text written by [SELECTION_SPAN_TRACKER_JS] on
     * 'selectionchange' — see the parallel comment on [withSelectionTextAndProgression]. Falls
     * back to a live [window.getSelection] read when the stash is empty (rotation / test seams).
     */
    private fun withSelectionText(block: (String) -> Unit) {
        evaluateJavascript("(function(){var s=window.__riffleSelData;if(s&&s.text)return s.text;return window.getSelection?window.getSelection().toString():'';})()") { raw ->
            val text = decodeJsString(raw)
            if (text.isNotBlank()) block(text)
            evalJs("window.__riffleSelData=null;window.getSelection && window.getSelection().removeAllRanges()")
        }
    }

    /**
     * Read the current selection's plain text, its within-document progression (0..1), the
     * selection's bounding rect in device pixels relative to this WebView, and ~60 chars of
     * document-text on each side of the selection, then run [block].
     *
     * progression = selectionTop / documentHeight — correct in Continuous mode because the WebView
     * never scrolls (pageYOffset=0), so getBoundingClientRect().top equals the absolute document
     * position. The rect is CSS px × devicePixelRatio so it composes with [getLocationOnScreen].
     *
     * The before/after context strings come from Range.toString() bracketing the selection — same
     * representation as TreeWalker.nodeValue concatenation, so render-time disambiguation can
     * exact-match without whitespace normalization. See [ContinuousStyleInjector.applyAnnotationHighlightsJs].
     */
    private fun withSelectionTextAndProgression(
        block: (text: String, progression: Double, rect: android.graphics.Rect, before: String, after: String) -> Unit,
    ) {
        evaluateJavascript(CONTINUOUS_SELECTION_READ_JS) { raw ->
            val jsonStr = decodeJsString(raw)
            val text: String
            val prog: Double
            val rect: android.graphics.Rect
            val before: String
            val after: String
            try {
                val obj = org.json.JSONObject(jsonStr)
                text = obj.optString("text", "")
                prog = obj.optDouble("p", 0.0)
                before = obj.optString("bef", "")
                after = obj.optString("aft", "")
                val dpr = resources.displayMetrics.density
                rect = android.graphics.Rect(
                    (obj.optDouble("l", 0.0) * dpr).toInt(),
                    (obj.optDouble("t", 0.0) * dpr).toInt(),
                    (obj.optDouble("r", 0.0) * dpr).toInt(),
                    (obj.optDouble("b", 0.0) * dpr).toInt(),
                )
                // Figures enclosed by the selection range — captured by SELECTION_SPAN_TRACKER_JS
                // while the range was still live (raster figures rasterised via canvas to a data
                // URI; SVG serialised verbatim). Stashed here so EpubReaderViewModel.createHighlight
                // can attach them to the highlight without needing a CFI→DOM resolver.
                val figuresJson = obj.optJSONArray("figures")
                if (figuresJson != null && figuresJson.length() > 0) {
                    val figures = mutableListOf<com.riffle.core.domain.EmbeddedFigure>()
                    for (i in 0 until figuresJson.length()) {
                        val f = figuresJson.optJSONObject(i) ?: continue
                        figures += com.riffle.core.domain.EmbeddedFigure(
                            href = f.optString("href").takeIf { !f.isNull("href") && it.isNotEmpty() },
                            svg = f.optString("svg").takeIf { !f.isNull("svg") && it.isNotEmpty() },
                            caption = f.optString("caption", ""),
                            order = f.optInt("order", i),
                            imageBytes = f.optString("bytes").takeIf { !f.isNull("bytes") && it.isNotEmpty() },
                        )
                    }
                    SelectionFiguresStash.set(figures)
                } else {
                    SelectionFiguresStash.set(emptyList())
                }
            } catch (_: Exception) {
                return@evaluateJavascript
            }
            if (text.isNotBlank()) block(text, prog, rect, before, after)
            evalJs("window.__riffleSelData=null;window.getSelection && window.getSelection().removeAllRanges()")
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            org.json.JSONArray("[$raw]").getString(0)
        } catch (_: Exception) {
            raw.trim('"')
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Riffle", text))
    }

    private fun webSearch(text: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, text)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) runCatching { context.startActivity(intent) }
    }

    private fun shareText(text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

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

        @JavascriptInterface
        fun onAnnotationTap(id: String, cssLeft: Float, cssTop: Float, cssRight: Float, cssBottom: Float) {
            post {
                val dpr = resources.displayMetrics.density
                val rect = android.graphics.Rect(
                    (cssLeft * dpr).toInt(),
                    (cssTop * dpr).toInt(),
                    (cssRight * dpr).toInt(),
                    (cssBottom * dpr).toInt(),
                )
                this@ChapterWebView.onAnnotationTap?.invoke(id, rect)
            }
        }

        @JavascriptInterface
        fun onAnnotationNoteTap(id: String, cssLeft: Float, cssTop: Float, cssRight: Float, cssBottom: Float) {
            post {
                val dpr = resources.displayMetrics.density
                val rect = android.graphics.Rect(
                    (cssLeft * dpr).toInt(),
                    (cssTop * dpr).toInt(),
                    (cssRight * dpr).toInt(),
                    (cssBottom * dpr).toInt(),
                )
                this@ChapterWebView.onAnnotationNoteTap?.invoke(id, rect)
            }
        }

        /**
         * Resolve the same-document anchor [id] against this chapter's HTML. Returns true (so the JS
         * suppresses the default in-page scroll) and posts the note body to [onFootnoteContent] when
         * the target is a footnote; false for a regular cross-reference (the JS then hands the tap
         * to [onCrossReferenceTap] below). Runs on the JS binder thread — Jsoup parsing here is safe.
         */
        @JavascriptInterface
        fun onFootnoteAnchorTap(id: String): Boolean {
            val html = rawChapterHtml ?: return false
            val doc = footnoteDoc ?: FootnoteResolver.parse(html).also { footnoteDoc = it }
            val content = FootnoteResolver.extractFootnoteContent(doc, id) ?: return false
            post { this@ChapterWebView.onFootnoteContent?.invoke(content) }
            return true
        }

        /**
         * Forwards a non-footnote same-document anchor tap ([id] is the fragment without '#') to the
         * main thread so the host can scroll the outer viewport to the target and offer a return
         * card. Runs on the JS binder thread; the [post] hop is what puts the callback on the main
         * thread. See [ContinuousScriptInjector.SAME_DOC_ANCHOR_LISTENER_JS] for why the JS side
         * always preventDefaults after calling this (the alternative desyncs child scrollY from the
         * parent's stacked-chapter geometry).
         */
        @JavascriptInterface
        fun onCrossReferenceTap(id: String) {
            post { this@ChapterWebView.onCrossReferenceTap?.invoke(id) }
        }

        /** Figure-tap event; [payload] is the JSON built by [FigureTapScript]. */
        @JavascriptInterface
        fun onFigureTap(payload: String) {
            post { this@ChapterWebView.onFigureTap?.invoke(payload) }
        }

        /**
         * Figure long-press event; [json] is the JSON built by [FigureTapScript]'s `touchstart`
         * listener. Parsed here (off the main thread, on the JS binder thread) via
         * [FigureLongPressMessageParser] before hopping to the main thread — same shape as
         * [onFootnoteAnchorTap]'s Jsoup parse above.
         */
        @JavascriptInterface
        fun onFigureLongPress(json: String) {
            val payload = FigureLongPressMessageParser.parse(json)
            post { this@ChapterWebView.onFigureLongPress?.invoke(payload) }
        }
    }
}

/**
 * Computes the clamped (top, bottom) for a selection content rect so that, when forwarded to the
 * floating ActionMode framework, it sits inside the host view's WINDOW-visible portion. The four
 * y-axis inputs are in window coordinates; output is in view-local (matches what onGetContentRect
 * is supposed to populate). When the WebView is fully outside the window viewport (or the rect was
 * already inside the visible band), the original top/bottom pass through unchanged. See the
 * call-site in [ChapterWebView.wrapSelectionCallback] for why this clamping is necessary on
 * edge-to-edge displays.
 */
internal fun clampSelectionYBandToWindow(
    rectTop: Int,
    rectBottom: Int,
    viewportTop: Int,
    viewportBottom: Int,
    viewLocationInWindowY: Int,
    viewHeight: Int,
): Pair<Int, Int> {
    val topLocal = (viewportTop - viewLocationInWindowY).coerceAtLeast(0)
    val bottomLocal = (viewportBottom - viewLocationInWindowY).coerceAtMost(viewHeight)
    if (bottomLocal <= topLocal) return rectTop to rectBottom
    return rectTop.coerceIn(topLocal, bottomLocal) to rectBottom.coerceIn(topLocal, bottomLocal)
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
