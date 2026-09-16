package com.riffle.shared.reader

import com.riffle.core.models.TocEntry
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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSJSONSerialization
import platform.Foundation.create

/**
 * iOS implementation of [EpubNavigatorInterface] that delegates to [IosEpubNavigatorBridge],
 * which is implemented on the Swift side using Readium Swift's EPUBNavigatorViewController.
 *
 * Readaloud-specific methods (followReadaloudSentence, measureCadenceColumns, etc.) are stubs
 * returning [NavigatorFollowResult.Unavailable] / empty lists — readaloud on iOS is out of scope
 * for v1.  Search, DOM patches, and continuous-mode scroll boundary are similarly deferred.
 */
class ReadiumSwiftNavigator(private val bridge: IosEpubNavigatorBridge) : EpubNavigatorInterface {

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
        bridge.setTapCallback {
            _eventFlow.tryEmit(NavigatorEvent.BodyTap)
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

    override suspend fun followCadenceSpan(fragmentId: String): NavigatorFollowResult =
        NavigatorFollowResult.Unavailable

    override suspend fun measureReadaloudColumns(text: String): List<Double> = emptyList()
    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {}
    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> = emptyList()
    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {}

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
    ) {
        bridge.applyReaderPreferences(
            fontSizePercent = fontSizePercent,
            scrollMode = scrollMode,
            theme = theme,
            fontFamilyCss = fontFamilyCss,
            lineHeightMultiplier = lineHeightMultiplier,
            pageMargins = pageMargins,
            justifyText = justifyText,
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

    private fun String.escapeForJson() = replace("\\", "\\\\").replace("\"", "\\\"")

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

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun parseTocJson(json: String): List<TocEntry> {
        val bytes = json.encodeToByteArray()
        val data = bytes.usePinned { p ->
            NSData.create(bytes = p.addressOf(0), length = bytes.size.toULong())
        }
        val array = NSJSONSerialization.JSONObjectWithData(data = data, options = 0u, error = null)
            as? NSArray ?: return emptyList()
        return parseTocArray(array)
    }

    private fun parseTocArray(array: NSArray): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        for (i in 0 until array.count.toLong()) {
            val dict = array.objectAtIndex(i.toULong()) as? NSDictionary ?: continue
            val title = dict.objectForKey("title") as? String ?: continue
            val href = dict.objectForKey("href") as? String ?: continue
            val childrenArray = dict.objectForKey("children") as? NSArray
            val children = childrenArray?.let { parseTocArray(it) } ?: emptyList()
            result += TocEntry(title = title, href = href, children = children)
        }
        return result
    }
}
