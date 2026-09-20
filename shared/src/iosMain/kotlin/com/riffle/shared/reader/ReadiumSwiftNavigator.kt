package com.riffle.shared.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.TocEntry
import com.riffle.feature.reader.ColumnSnap
import com.riffle.feature.reader.EpubNavigatorInterface
import com.riffle.feature.reader.LocatorJson
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationOptions
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorScrollBoundary
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.cadence.CadenceDomScript
import com.riffle.feature.reader.cadence.CadenceInjector
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSJSONSerialization
import platform.Foundation.create
import kotlin.coroutines.resume

/**
 * iOS implementation of [EpubNavigatorInterface] that delegates to [IosEpubNavigatorBridge],
 * which is implemented on the Swift side using Readium Swift's EPUBNavigatorViewController.
 *
 * Cadence and Readaloud share the sentence-follow surface: both drive `feature:reader`'s
 * [ColumnSnap] JS through the bridge's `evaluateJavaScript` seam, so the column arithmetic is
 * the same code Android runs. DOM highlight patches and the continuous-mode scroll boundary have
 * no iOS analogue — Readium owns the scroll in both of iOS's modes.
 */
class ReadiumSwiftNavigator(
    private val bridge: IosEpubNavigatorBridge,
    private val logger: Logger = KoinPlatform.getKoin().get(),
) : EpubNavigatorInterface {

    private val _positionFlow = MutableSharedFlow<NavigatorPosition>(replay = 1, extraBufferCapacity = 64)
    private val _pageLoadEvents = MutableSharedFlow<NavigatorPageLoad>(extraBufferCapacity = 16)
    private val _eventFlow = MutableSharedFlow<NavigatorEvent>(extraBufferCapacity = 16)
    private var pageLoadGeneration = 0
    private var lastPosition: NavigatorPosition? = null

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
        // BodyTap has no collector on iOS yet (#1071 §17). It is not dead wiring that can be
        // deleted: [eventFlow] is part of the EpubNavigatorInterface contract, and dropping the
        // emission would leave it permanently empty rather than merely unread. Its consumer on
        // Android is immersive mode (EpubReaderScreen's `onTap = immersiveState::toggle`), and
        // iOS's reader renders permanently-visible chrome with no immersive state to toggle —
        // that surface is #1072.
        bridge.setTapCallback {
            _eventFlow.tryEmit(NavigatorEvent.BodyTap)
        }
        // #1071 §17: Readium's presentError was an empty Swift stub, so a navigator failure left
        // no trace anywhere. Logging it is the honest minimum — there is no iOS error surface in
        // the reader chrome to raise it to yet (#1072).
        bridge.setErrorCallback { message ->
            logger.e(LogChannel.Reader) { "navigator error: $message" }
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
        bridge.disposeNavigator()
    }

    override val positionFlow: Flow<NavigatorPosition> = _positionFlow
    override val pageLoadEvents: Flow<NavigatorPageLoad> = _pageLoadEvents
    override val viewportFractionEvents: Flow<Pair<String, Double>> = emptyFlow()
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

    override suspend fun getChapterBytes(href: String): ByteArray? = null

    override suspend fun scrollBoundary(): NavigatorScrollBoundary = NavigatorScrollBoundary.None

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
}
