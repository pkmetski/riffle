package com.riffle.shared.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.TocEntry
import com.riffle.feature.reader.ColumnSnap
import com.riffle.feature.reader.EpubNavigatorInterface
import com.riffle.feature.reader.LocatorJson
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorDecorationActivation
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationOptions
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorRect
import com.riffle.feature.reader.NavigatorScrollBoundary
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.NavigatorSelection
import com.riffle.feature.reader.ScrollProbes
import com.riffle.feature.reader.cadence.CadenceDomScript
import com.riffle.feature.reader.cadence.CadenceInjector
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNumber
import platform.Foundation.create
import kotlin.coroutines.resume

/**
 * iOS implementation of [EpubNavigatorInterface] that delegates to [IosEpubNavigatorBridge],
 * which is implemented on the Swift side using Readium Swift's EPUBNavigatorViewController.
 *
 * Cadence and Readaloud share the sentence-follow surface: both drive `feature:reader`'s
 * [ColumnSnap] JS through the bridge's `evaluateJavaScript` seam, so the column arithmetic is
 * the same code Android runs.
 *
 * The scroll-state probes ([scrollBoundary], [publishViewportFraction]) run the same shared
 * [ScrollProbes] scripts Android's `DefaultRendererBridge` runs. They are what gives iOS a
 * Continuous mode at all: Readium owns the scroll in both of iOS's scrolling modes and renders
 * one resource at a time, so the reader has to *detect* the chapter boundary in order to cross
 * it — see [com.riffle.feature.reader.ContinuousBoundaryAdvancePolicy].
 *
 * DOM highlight patches still have no iOS analogue; Readium-Swift's decoration templates cover
 * what Android needs the patch for.
 */
class ReadiumSwiftNavigator(
    private val bridge: IosEpubNavigatorBridge,
    private val logger: Logger = KoinPlatform.getKoin().get(),
) : EpubNavigatorInterface {

    private val _positionFlow = MutableSharedFlow<NavigatorPosition>(replay = 1, extraBufferCapacity = 64)
    private val _pageLoadEvents = MutableSharedFlow<NavigatorPageLoad>(extraBufferCapacity = 16)
    private val _eventFlow = MutableSharedFlow<NavigatorEvent>(extraBufferCapacity = 16)
    private val _selectionFlow = MutableStateFlow<NavigatorSelection?>(null)
    private val _decorationActivations =
        MutableSharedFlow<NavigatorDecorationActivation>(extraBufferCapacity = 16)
    private val _viewportFractionEvents =
        MutableSharedFlow<Pair<String, Double>>(replay = 0, extraBufferCapacity = 64)
    private val _figureTapPayloads = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var pageLoadGeneration = 0
    private var lastPosition: NavigatorPosition? = null

    // Tracks whether the reader is in paginated (non-scroll) mode. Null until the first
    // applyReaderPreferences call; edge-tap navigation is suppressed while null so an early tap
    // before Compose's LaunchedEffect fires does not accidentally navigate in vertical/scroll mode.
    private var isPaginatedMode: Boolean? = null

    /**
     * The live text selection, or null when there is none.
     *
     * A [MutableStateFlow] rather than a SharedFlow because the annotate sheet's visibility IS
     * this value: the reader shows it while a selection exists and dismisses it when Readium
     * reports the selection cleared, so a late collector must see the current state, not wait
     * for the next change.
     */
    val selectionFlow: StateFlow<NavigatorSelection?> = _selectionFlow

    /** Taps on a rendered decoration, keyed by decoration group. */
    val decorationActivations: Flow<NavigatorDecorationActivation> = _decorationActivations

    /**
     * Raw JSON payloads from the JS figure-tap bridge.
     *
     * Emitted whenever a JS figure-tap event arrives. Parsed by
     * [com.riffle.feature.reader.FigureTapMessageParser.parse] — collectors that need the typed
     * state use that parser rather than dealing with raw JSON.
     */
    val figureTapPayloads: Flow<String> = _figureTapPayloads

    private fun registerBridgeCallbacks() {
        bridge.setLocatorCallback { json ->
            parseLocatorJson(json)?.let { pos ->
                lastPosition = pos
                _positionFlow.tryEmit(pos)
            }
        }
        bridge.setPageLoadCallback {
            pageLoadGeneration++
            _pageLoadEvents.tryEmit(NavigatorPageLoad(pageLoadGeneration))
        }
        // In paginated mode, taps in the left/right edge mid-vertical band turn the page.
        // All other taps emit BodyTap, which dismisses any open annotation sheet on iOS.
        // (Android handles the same logic in EpubReaderScreen's InputListener.onTap, where
        // Readium's TapEvent carries the coordinates directly.)
        bridge.setTapCallback { x, y, viewWidth, viewHeight ->
            if (isPaginatedMode == true && viewWidth > 0 && viewHeight > 0) {
                val xFrac = x / viewWidth
                val yFrac = y / viewHeight
                if (yFrac > PAGE_EDGE_TAP_VERTICAL_GUARD && yFrac < 1f - PAGE_EDGE_TAP_VERTICAL_GUARD) {
                    when {
                        xFrac < PAGE_EDGE_TAP_FRACTION -> { bridge.goBackward(); return@setTapCallback }
                        xFrac > 1f - PAGE_EDGE_TAP_FRACTION -> { bridge.goForward(); return@setTapCallback }
                    }
                }
            }
            _eventFlow.tryEmit(NavigatorEvent.BodyTap)
        }
        // #1071 §17: Readium's presentError was an empty Swift stub, so a navigator failure left
        // no trace anywhere. Logging it is the honest minimum — there is no iOS error surface in
        // the reader chrome to raise it to yet (#1072).
        bridge.setErrorCallback { message ->
            logger.e(LogChannel.Reader) { "navigator error: $message" }
        }
        bridge.setSelectionCallback { json ->
            _selectionFlow.value = json?.let { parseSelectionJson(it) }
        }
        bridge.setDecorationActivatedCallback { json ->
            parseActivationJson(json)?.let { _decorationActivations.tryEmit(it) }
        }
        bridge.setFigureTapCallback { payload ->
            _figureTapPayloads.tryEmit(payload)
        }
    }

    init {
        registerBridgeCallbacks()
    }

    override suspend fun open(bookFilePath: String, initialLocatorJson: LocatorJson?) {
        // Re-register in case close() was called and callbacks were cleared for a prior open.
        registerBridgeCallbacks()
        bridge.openEpub(bookFilePath, initialLocatorJson)
    }

    /** Open an O'Reilly lazy publication without downloading a file first. */
    fun openLazy(shapeJson: String, locatorJson: LocatorJson?, fetcher: IosLazyChapterFetcher) {
        registerBridgeCallbacks()
        bridge.openLazyEpub(shapeJson, locatorJson, fetcher)
    }

    override fun close() {
        bridge.setLocatorCallback(null)
        bridge.setPageLoadCallback(null)
        bridge.setTapCallback(null)
        bridge.setErrorCallback(null)
        bridge.setSelectionCallback(null)
        bridge.setDecorationActivatedCallback(null)
        bridge.setFigureTapCallback(null)
        _selectionFlow.value = null
        bridge.disposeNavigator()
    }

    /** Drop the live selection — called once the user has acted on it. */
    fun clearSelection() {
        bridge.clearSelection()
        _selectionFlow.value = null
    }

    /**
     * Make [group]'s decorations tappable. Readium only dispatches taps for groups registered
     * this way; an unregistered group renders but is inert.
     */
    fun observeDecorationGroup(group: String) {
        bridge.observeDecorationGroup(group)
    }

    override val positionFlow: Flow<NavigatorPosition> = _positionFlow
    override val pageLoadEvents: Flow<NavigatorPageLoad> = _pageLoadEvents

    /**
     * Per-resource `viewportSize / chapterSize`, keyed by the *normalised* href.
     *
     * Normalised at the source rather than at the consumer because the only consumer,
     * [com.riffle.feature.reader.bookmarkEpsFor], looks the map up by a normalised href — a raw
     * Readium href would simply never hit, silently dropping the reader back onto the flat
     * `BOOKMARK_PAGE_EPS` fallback and lighting the corner ribbon across three or four pages.
     *
     * Published from [publishViewportFraction], which the reader calls on page load and after a
     * typography change — the two moments Readium re-lays the document out. Never on scroll:
     * the measurement does not change with scroll position, and re-emitting per frame is what
     * flaked Android's equivalent (issue #399).
     */
    override val viewportFractionEvents: Flow<Pair<String, Double>> = _viewportFractionEvents
    override val eventFlow: Flow<NavigatorEvent> = _eventFlow

    override suspend fun navigateTo(target: NavigatorNavigationTarget, options: NavigatorNavigationOptions) {
        when (target) {
            is NavigatorNavigationTarget.ToLocatorJson -> bridge.goToLocator(target.locatorJson)
            is NavigatorNavigationTarget.ToHref -> {
                val href = target.href.escapeForJson()
                val fragment = target.fragment?.let { f ->
                    ""","locations":{"fragments":["${f.escapeForJson()}"]}"""
                } ?: ""
                bridge.goToLocator("""{"href":"$href","type":"application/xhtml+xml"$fragment}""")
            }
            is NavigatorNavigationTarget.ToProgression -> {
                val href = target.href.escapeForJson()
                bridge.goToLocator(
                    """{"href":"$href","type":"application/xhtml+xml","locations":{"progression":${target.progression}}}"""
                )
            }
        }
    }

    override suspend fun pageBy(direction: NavigatorPageDirection) {
        when (direction) {
            NavigatorPageDirection.Forward -> bridge.goForward()
            NavigatorPageDirection.Backward -> bridge.goBackward()
        }
    }

    override fun applyDecorations(group: String, decorations: List<NavigatorDecoration>) {
        bridge.applyDecorations(serializeDecorations(decorations), group)
    }

    private fun serializeDecorations(decorations: List<NavigatorDecoration>): String {
        val items = decorations.joinToString(",") { d ->
            when (d) {
                is NavigatorDecoration.Highlight ->
                    """{"id":"${d.id.escapeForJson()}","type":"highlight","locator":${d.locatorJson},"color":"${d.color.escapeForJson()}","alpha":${d.alpha}}"""
                is NavigatorDecoration.Bookmark ->
                    """{"id":"${d.id.escapeForJson()}","type":"bookmark","locator":${d.locatorJson}}"""
                is NavigatorDecoration.NoteGlyph ->
                    """{"id":"${d.id.escapeForJson()}","type":"noteGlyph","locator":${d.locatorJson}}"""
                is NavigatorDecoration.SearchMark ->
                    """{"id":"${d.id.escapeForJson()}","type":"searchMark","locator":${d.locatorJson},"isCurrent":${d.isCurrent}}"""
                is NavigatorDecoration.Emphasis -> {
                    val tokens = d.styles.joinToString(",") { it.token }
                    """{"id":"${d.id.escapeForJson()}","type":"emphasis","locator":${d.locatorJson},"styles":"$tokens"}"""
                }
            }
        }
        return "[$items]"
    }

    override suspend fun applyHighlightDomPatch(patchJson: String) {}

    override suspend fun followReadaloudSentence(text: String): NavigatorFollowResult =
        NavigatorFollowResult.Unavailable

    /**
     * Bring Cadence's `cd-N` span onto the page.
     *
     * Three-way outcome, identical to Android's `ReadiumPresenter.followCadenceSpan`: `"moved"`
     * (the snap changed the page), `"same"` (already on-page) and `"absent"` (the id is not in
     * this resource, so the caller navigates to its chapter). Collapsing `"same"` into
     * [NavigatorFollowResult.OffPage] would fire a chapter navigation on every tick while the
     * sentence sits comfortably visible.
     *
     * `animated = false` for the same reason Android passes it: the follow ticks once per
     * sentence and a 250 ms tween per tick visibly drifts, because the supersede counter cancels
     * in-flight animations before they land.
     */
    override suspend fun followCadenceSpan(fragmentId: String): NavigatorFollowResult =
        when (evaluateJs(ColumnSnap.scrollToColumnJs(fragmentId, animated = false))?.trim('"')) {
            "moved", "same" -> NavigatorFollowResult.Snapped
            "absent" -> NavigatorFollowResult.OffPage
            else -> NavigatorFollowResult.Unavailable
        }

    override suspend fun measureReadaloudColumns(text: String): List<Double> =
        ColumnSnap.parseNarratedColumnsResult(evaluateJs(ColumnSnap.measureNarratedColumnsJs(text)))

    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {
        evaluateJs(ColumnSnap.snapNarratedColumnJs(text, columnIndex))
    }

    /**
     * The fractions of the sentence that fall in each paginated column it spans.
     *
     * Non-empty only in Readium's paginated mode: the shared JS returns the bare token `"scroll"`
     * when the document scrolls (Vertical and Continuous both map there via [epubScrollMode]),
     * and [ColumnSnap.parseNarratedColumnsResult] turns that into an empty list — which the
     * caller reads as "this mode has no column grid, do not drive intra-sentence page turns".
     */
    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> =
        ColumnSnap.parseNarratedColumnsResult(evaluateJs(ColumnSnap.measureCadenceColumnsJs(fragmentId)))

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {
        evaluateJs(ColumnSnap.snapCadenceColumnJs(fragmentId, columnIndex))
    }

    // ── Cadence DOM pipeline ────────────────────────────────────────────────────
    //
    // The scripts are the shared ones in `feature:reader`; only running them is host-specific.

    /** True when the WebView has `Intl.Segmenter`. Cadence has no fallback tokeniser (issue #403). */
    internal suspend fun cadenceFeatureDetect(): Boolean? =
        when (evaluateJs(CadenceDomScript.FEATURE_DETECT_JS)?.trim('"')?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /**
     * Wrap every sentence of the currently-rendered chapter in a `<span id="cd-N">` and return the
     * `FragmentRef → SentenceQuote` / `FragmentRef → chapterHref` pair.
     *
     * Idempotent per chapter: the script bails out and re-reads the existing spans when it finds
     * them, which matters because Readium re-reports a resource load on reflow and on backward
     * turns.
     */
    internal suspend fun cadenceTokeniseChapter(
        chapterHref: String,
        localeTag: String?,
    ): CadenceInjector.Result =
        CadenceInjector.parse(evaluateJs(CadenceDomScript.tokeniseChapterJs(chapterHref, localeTag)))

    /**
     * The id (`"chapter#cd-N"`, or a bare `"cd-N"`) of the sentence Cadence should start from —
     * the section-aware probe of what the reader is actually looking at.
     *
     * Nulls for the viewport bounds let the JS read `window.scrollY` / `innerHeight`, which is
     * correct here for both Readium modes: the WKWebView owns its own scroll in paginated and in
     * scroll mode alike. Only Android's Continuous reader, whose `ChapterWebView`s do not scroll
     * themselves, has to project the parent scroll container's bounds in.
     */
    internal suspend fun cadenceStartSpanId(): String? =
        CadenceDomScript.parseCadenceStartId(evaluateJs(CadenceDomScript.cadenceStartSpanIdJs()))

    /**
     * The DOM id of the first block element visible in the current column, or null.
     *
     * The element-anchored half of bookmark navigation (PR #671): stored on the bookmark as
     * `fragmentAnchor`, it goes back into the locator as `locations.fragments` so returning to
     * the bookmark lands on the paragraph rather than on a progression estimate that a font-size
     * change has since invalidated.
     *
     * The shared script answers null for a scrolling document, which is the same "no anchor"
     * shape a legacy bookmark has — so Vertical and Continuous simply keep the progression path.
     */
    internal suspend fun capturePageFragmentAnchor(): String? {
        val raw = evaluateJs(ColumnSnap.CAPTURE_PAGE_FRAGMENT_ANCHOR_JS) ?: return null
        val trimmed = raw.trim('"')
        return if (trimmed == "null" || trimmed.isBlank()) null else trimmed
    }

    /**
     * The publisher's computed `font-family` on `<body>`, for `AnnotationEntity.originFontFamily`
     * (issue #484). Null when there is no navigator or the script threw.
     */
    internal suspend fun computedBodyFontFamily(): String? {
        val raw = evaluateJs("getComputedStyle(document.body).fontFamily") ?: return null
        val trimmed = raw.trim('"')
        return if (trimmed == "null" || trimmed.isBlank()) null else trimmed
    }

    /**
     * Run a shared decoration script (figure borders, the bold/italic wrap) in the live document.
     *
     * Neither can be a Readium decoration: a decoration anchors to a text range — an `<img>` has
     * none — and an overlay cannot reflow text the way bold does. Android issues the identical
     * scripts through `RendererBridge.evaluateJavascript`; this is the same seam.
     */
    internal suspend fun evaluateJavaScriptForDecorations(script: String) {
        evaluateJs(script)
    }

    /**
     * Inject the figure-tap interceptor script into the current page.
     *
     * The shim that maps [com.riffle.feature.reader.FigureTapScript]'s
     * `window.RiffleFigureBridge.onFigureTap(p)` call convention to
     * `window.webkit.messageHandlers.RiffleFigureBridge.postMessage(p)` is added once per
     * WKWebView lifecycle via [navigator(_:setupUserScripts:)], so it is already present before
     * the page starts loading.  This re-injects the tap listener itself (which must run after DOM
     * is ready) on every page-load event — the same cadence Android's [FigureTapBridge] uses when
     * it calls [installScript] from [onPageStarted].
     */
    internal suspend fun injectFigureTapScript() {
        evaluateJs(com.riffle.feature.reader.FigureTapScript.installScript(
            com.riffle.feature.reader.FigureTapScript.PAGED_BRIDGE_NAME
        ))
    }

    private suspend fun evaluateJs(script: String): String? = suspendCancellableCoroutine { cont ->
        bridge.evaluateJavaScript(script) { result -> if (cont.isActive) cont.resume(result) }
    }

    override suspend fun search(query: String): Flow<List<NavigatorSearchMatch>> = callbackFlow {
        bridge.startSearch(
            query = query,
            onBatch = { json -> trySend(parseSearchMatches(json)) },
            onDone = { close() },
        )
        awaitClose { bridge.cancelSearch() }
    }

    /** Fetch the TOC from the open publication. Returns empty list if no publication is open. */
    fun getToc(): List<TocEntry> = parseTocJson(bridge.getTocJson())

    /**
     * Fetch the reading order and per-resource position counts of the open publication — the
     * inputs the shared rail generator weights chapter-map segments with. Returns
     * [SpinePositions.Empty] until Readium has finished computing positions.
     */
    internal fun getSpine(): SpinePositions = parseSpineJson(bridge.getSpineJson())

    /**
     * Scroll the visible resource down by [pixels] device pixels; returns false when the document
     * did not move, which auto-scroll reads as "end of this resource".
     */
    internal suspend fun scrollByPx(pixels: Int): Boolean = suspendCancellableCoroutine { cont ->
        bridge.scrollByPx(pixels) { moved -> if (cont.isActive) cont.resume(moved) }
    }

    override fun snapshotPosition(): NavigatorPosition? = lastPosition
        ?: bridge.snapshotLocatorJson()?.let { parseLocatorJson(it) }

    /**
     * The chapter's *source* XHTML, straight from the publication.
     *
     * Android reads the same bytes out of the EPUB zip (`EpubReaderViewModel.readChapterHtml`).
     * It is what every shared annotation derivation is computed against, so it must be the
     * unmodified resource — not `document.documentElement.outerHTML` from the live WKWebView,
     * which carries Readium's injected scripts and, once Cadence has run, a `<span class=
     * "riffle-cd">` around every sentence. The readable-character offsets those produce do not
     * match Android's, and a CFI built from them points at the wrong text on the other device.
     */
    override suspend fun getChapterBytes(href: String): ByteArray? =
        readChapterHtml(href)?.encodeToByteArray()

    /** [getChapterBytes] as the string the shared derivations actually take. */
    internal suspend fun readChapterHtml(href: String): String? =
        suspendCancellableCoroutine { cont ->
            bridge.readResource(href) { html -> if (cont.isActive) cont.resume(html) }
        }

    /**
     * Load a non-text resource (e.g. cover image, chapter illustration) and return its raw bytes.
     *
     * The Swift bridge reads the bytes from the open [Publication], encodes them as Base64, and
     * returns the string here; this function decodes that back to a [ByteArray] so callers never
     * see the Base64 encoding detail.  Returns `null` when the publication is closed or the href
     * is not in the publication's reading order.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    internal suspend fun readResourceBytes(href: String): ByteArray? {
        val base64 = suspendCancellableCoroutine<String?> { cont ->
            bridge.readResourceBase64(href) { b64 -> if (cont.isActive) cont.resume(b64) }
        } ?: return null
        return kotlin.io.encoding.Base64.decode(base64)
    }

    /**
     * Where the visible resource's scroll sits — at its top, at its bottom, or in between.
     *
     * The whole reason Continuous mode did not exist on iOS. Readium-Swift's
     * `EPUBNavigatorViewController` renders one resource at a time in `scroll` mode and stops at
     * its end, so without this probe there was no way for the reader to know it had arrived at a
     * chapter boundary and cross it by itself — Continuous and Vertical rendered identically.
     *
     * One round trip, not Android's two: see [ScrollProbes.BOUNDARY_PROBE_JS]. Answers
     * [NavigatorScrollBoundary.None] in paginated mode, where the document overflows horizontally
     * and `window.scrollY` never moves — which is exactly the contract
     * [com.riffle.feature.reader.ContinuousBoundaryAdvancePolicy] needs: no boundary, no advance.
     */
    override suspend fun scrollBoundary(): NavigatorScrollBoundary =
        ScrollProbes.parseScrollBoundary(evaluateJs(ScrollProbes.BOUNDARY_PROBE_JS))

    /**
     * Measure the visible resource and publish it on [viewportFractionEvents] against
     * [normalizedHref]. No-ops when the measurement is unusable, so a failed probe leaves the
     * previous (good) value in place rather than replacing it with a zero.
     */
    internal suspend fun publishViewportFraction(normalizedHref: String) {
        if (normalizedHref.isEmpty()) return
        val fraction = ScrollProbes.parseViewportFraction(
            evaluateJs(ScrollProbes.VIEWPORT_FRACTION_JS),
        ) ?: return
        _viewportFractionEvents.tryEmit(normalizedHref to fraction)
    }

    fun applyReaderPreferences(
        fontSizePercent: Float,
        scrollMode: Boolean,
        theme: String,
        fontFamilyCss: String,
        lineHeightMultiplier: Float,
        pageMargins: Double,
        justifyText: Boolean,
        textColorArgb: Long,
        publisherStyles: Boolean,
        columnCount: Int,
    ) {
        isPaginatedMode = !scrollMode
        bridge.applyReaderPreferences(
            IosReaderPreferences(
                fontSizePercent = fontSizePercent,
                scrollMode = scrollMode,
                theme = theme,
                fontFamilyCss = fontFamilyCss,
                lineHeightMultiplier = lineHeightMultiplier,
                pageMargins = pageMargins,
                justifyText = justifyText,
                textColorArgb = textColorArgb,
                publisherStyles = publisherStyles,
                columnCount = columnCount,
            ),
        )
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun parseLocatorJson(json: String): NavigatorPosition? {
        val bytes = json.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val parsed = NSJSONSerialization.JSONObjectWithData(
            data = data,
            options = 0u,
            error = null,
        ) as? platform.Foundation.NSDictionary ?: return null

        val href = parsed.objectForKey("href") as? String ?: return null
        val locations = parsed.objectForKey("locations") as? platform.Foundation.NSDictionary
        val progression = (locations?.objectForKey("progression") as? platform.Foundation.NSNumber)
            ?.floatValue ?: 0f
        val totalProgression = (locations?.objectForKey("totalProgression") as? platform.Foundation.NSNumber)
            ?.floatValue

        return NavigatorPosition(
            href = href,
            progression = progression,
            totalProgression = totalProgression,
            locatorJson = json,
        )
    }

    // Delegate to the shared utility in JsonStringUtils.kt (commonMain).
    private fun String.escapeForJson() = jsonEscaped()

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun parseJsonObject(json: String): NSDictionary? {
        val bytes = json.encodeToByteArray()
        if (bytes.isEmpty()) return null
        val data = bytes.usePinned { p ->
            NSData.create(bytes = p.addressOf(0), length = bytes.size.toULong())
        }
        return NSJSONSerialization.JSONObjectWithData(data = data, options = 0u, error = null)
            as? NSDictionary
    }

    private fun NSDictionary.doubleOrNull(key: String): Double? =
        (objectForKey(key) as? NSNumber)?.doubleValue

    private fun NSDictionary.rect(): NavigatorRect? {
        val x = doubleOrNull("x") ?: return null
        val y = doubleOrNull("y") ?: return null
        val width = doubleOrNull("width") ?: return null
        val height = doubleOrNull("height") ?: return null
        return NavigatorRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
    }

    /** See [IosEpubNavigatorBridge.setSelectionCallback] for the payload shape. */
    internal fun parseSelectionJson(json: String): NavigatorSelection? {
        val dict = parseJsonObject(json) ?: return null
        val text = dict.objectForKey("text") as? String ?: return null
        // A whitespace-only selection is what a stray double-tap on a margin produces; treating
        // it as a real selection pops the annotate sheet over nothing.
        if (text.isBlank()) return null
        return NavigatorSelection(
            locatorJson = dict.objectForKey("locatorJson") as? String ?: return null,
            href = dict.objectForKey("href") as? String ?: return null,
            text = text,
            before = dict.objectForKey("before") as? String ?: "",
            after = dict.objectForKey("after") as? String ?: "",
            progression = dict.doubleOrNull("progression") ?: 0.0,
            rect = dict.rect(),
        )
    }

    /** See [IosEpubNavigatorBridge.setDecorationActivatedCallback] for the payload shape. */
    internal fun parseActivationJson(json: String): NavigatorDecorationActivation? {
        val dict = parseJsonObject(json) ?: return null
        return NavigatorDecorationActivation(
            id = dict.objectForKey("id") as? String ?: return null,
            group = dict.objectForKey("group") as? String ?: return null,
            rect = dict.rect(),
        )
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun parseSearchMatches(json: String): List<NavigatorSearchMatch> {
        val bytes = json.encodeToByteArray()
        val data = bytes.usePinned { p ->
            NSData.create(bytes = p.addressOf(0), length = bytes.size.toULong())
        }
        val array = NSJSONSerialization.JSONObjectWithData(data = data, options = 0u, error = null)
            as? NSArray ?: return emptyList()
        val result = mutableListOf<NavigatorSearchMatch>()
        for (i in 0 until array.count.toLong()) {
            val dict = array.objectAtIndex(i.toULong()) as? NSDictionary ?: continue
            val locatorJson = dict.objectForKey("locatorJson") as? String ?: continue
            val snippet = dict.objectForKey("snippet") as? String ?: ""
            result += NavigatorSearchMatch(locatorJson = locatorJson, snippet = snippet)
        }
        return result
    }

    companion object {
        // Mirror of EpubReaderScreen.PAGE_EDGE_TAP_FRACTION / PAGE_EDGE_TAP_VERTICAL_GUARD.
        private const val PAGE_EDGE_TAP_FRACTION = 0.20f
        private const val PAGE_EDGE_TAP_VERTICAL_GUARD = 0.15f
    }
}
