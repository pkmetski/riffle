package com.riffle.shared.reader

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSData
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

    override fun applyDecorations(group: String, decorations: List<NavigatorDecoration>) {}

    override suspend fun applyHighlightDomPatch(patchJson: String) {}

    override suspend fun followReadaloudSentence(text: String): NavigatorFollowResult =
        NavigatorFollowResult.Unavailable

    override suspend fun followCadenceSpan(fragmentId: String): NavigatorFollowResult =
        NavigatorFollowResult.Unavailable

    override suspend fun measureReadaloudColumns(text: String): List<Double> = emptyList()
    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {}
    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> = emptyList()
    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {}

    override suspend fun search(query: String): Flow<List<NavigatorSearchMatch>> = emptyFlow()

    override fun snapshotPosition(): NavigatorPosition? = lastPosition
        ?: bridge.snapshotLocatorJson()?.let { parseLocatorJson(it) }

    override suspend fun getChapterBytes(href: String): ByteArray? = null

    override suspend fun scrollBoundary(): NavigatorScrollBoundary = NavigatorScrollBoundary.None

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
}
