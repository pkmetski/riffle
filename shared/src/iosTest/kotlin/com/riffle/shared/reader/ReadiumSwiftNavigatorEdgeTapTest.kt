package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the edge-tap page-turn logic in [ReadiumSwiftNavigator].
 *
 * Left/right edge taps inside the mid-vertical band of a paginated view call [bridge.goBackward] /
 * [bridge.goForward] directly. All other taps (center, top/bottom band, scroll mode) emit
 * [NavigatorEvent.BodyTap] instead.
 *
 * Each assertion here would flip red if the edge-zone check were removed or the directional
 * mapping (left→Backward, right→Forward) were accidentally swapped.
 */
class ReadiumSwiftNavigatorEdgeTapTest {

    private class RecordingBridge : IosEpubNavigatorBridge {
        var tapCallback: ((Float, Float, Float, Float) -> Unit)? = null
        var goForwardCalls = 0
        var goBackwardCalls = 0

        override fun setTapCallback(callback: ((Float, Float, Float, Float) -> Unit)?) {
            tapCallback = callback
        }
        override fun goForward() { goForwardCalls++ }
        override fun goBackward() { goBackwardCalls++ }

        override fun viewController(): UIViewController = UIViewController()
        override fun openEpub(filePath: String, locatorJson: String?) = Unit
        override fun goToLocator(locatorJson: String) = Unit
        override fun snapshotLocatorJson(): String? = null
        override fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?) = Unit
        override fun setPageLoadCallback(callback: (() -> Unit)?) = Unit
        override fun setErrorCallback(callback: ((message: String) -> Unit)?) = Unit
        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) = Unit
        override fun clearSelection() = Unit
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) = Unit
        override fun observeDecorationGroup(group: String) = Unit
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun readResource(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) = onResult(null)
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(true)
        override fun startSearch(query: String, onBatch: ((String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
    }

    private fun navigator(bridge: RecordingBridge = RecordingBridge()) =
        ReadiumSwiftNavigator(bridge, RecordingLogger())

    @Test
    fun leftEdgeMidBand_callsGoBackward() {
        val bridge = RecordingBridge()
        navigator(bridge)
        // x=50/360=0.139 < 0.20 edge fraction, y=400/800=0.50 inside mid band
        bridge.tapCallback?.invoke(50f, 400f, 360f, 800f)
        assertEquals(1, bridge.goBackwardCalls)
        assertEquals(0, bridge.goForwardCalls)
    }

    @Test
    fun rightEdgeMidBand_callsGoForward() {
        val bridge = RecordingBridge()
        navigator(bridge)
        // x=310/360=0.861 > 0.80, y=400/800=0.50 inside mid band
        bridge.tapCallback?.invoke(310f, 400f, 360f, 800f)
        assertEquals(1, bridge.goForwardCalls)
        assertEquals(0, bridge.goBackwardCalls)
    }

    @Test
    fun centerTap_emitsBodyTap() = runTest {
        val bridge = RecordingBridge()
        val nav = navigator(bridge)
        bridge.tapCallback?.invoke(180f, 400f, 360f, 800f)
        val event = nav.eventFlow.first()
        assertEquals(NavigatorEvent.BodyTap, event)
        assertEquals(0, bridge.goForwardCalls)
        assertEquals(0, bridge.goBackwardCalls)
    }

    @Test
    fun leftEdgeTopBand_emitsBodyTap() = runTest {
        val bridge = RecordingBridge()
        val nav = navigator(bridge)
        // y=60/800=0.075 < 0.15 vertical guard — excluded from edge navigation
        bridge.tapCallback?.invoke(50f, 60f, 360f, 800f)
        val event = nav.eventFlow.first()
        assertEquals(NavigatorEvent.BodyTap, event)
        assertEquals(0, bridge.goBackwardCalls)
    }

    @Test
    fun scrollMode_leftEdge_emitsBodyTapInsteadOfNavigating() = runTest {
        val bridge = RecordingBridge()
        val nav = navigator(bridge)
        nav.applyReaderPreferences(
            fontSizePercent = 100f,
            scrollMode = true,
            theme = "light",
            fontFamilyCss = "",
            lineHeightMultiplier = 0f,
            pageMargins = 1.0,
            justifyText = false,
            textColorArgb = 0L,
            publisherStyles = false,
            columnCount = 0,
        )
        bridge.tapCallback?.invoke(50f, 400f, 360f, 800f)
        val event = nav.eventFlow.first()
        assertEquals(NavigatorEvent.BodyTap, event)
        assertEquals(0, bridge.goBackwardCalls)
    }
}
