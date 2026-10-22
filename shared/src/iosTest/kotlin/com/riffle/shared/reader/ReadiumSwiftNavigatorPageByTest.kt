package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorPageDirection
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that boundary-triggered chapter crossings in Continuous mode use the instant (non-animated)
 * bridge calls, not the animated ones.
 *
 * [ReadiumSwiftNavigator.pageBy] is called only from the boundary-detection loop and the
 * auto-scroll stall action — both Continuous mode paths. If it were to call the animated variants
 * it would trigger Readium-Swift's PaginationView.slideToView: a 300 ms horizontal swipe that is
 * visually wrong for vertical Continuous reading and which was the root cause of the "occasional
 * horizontal swipe that navigates chapters" report.
 *
 * Each assertion would flip red if pageBy were reverted to call goForward/goBackward instead of
 * goForwardInstant/goBackwardInstant.
 */
class ReadiumSwiftNavigatorPageByTest {

    private class RecordingBridge : IosEpubNavigatorBridge {
        var goForwardCalls = 0
        var goBackwardCalls = 0
        var goForwardInstantCalls = 0
        var goBackwardInstantCalls = 0

        override fun goForward() { goForwardCalls++ }
        override fun goBackward() { goBackwardCalls++ }
        override fun goForwardInstant() { goForwardInstantCalls++ }
        override fun goBackwardInstant() { goBackwardInstantCalls++ }

        override fun viewController(): UIViewController = UIViewController()
        override fun openEpub(filePath: String, locatorJson: String?) = Unit
        override fun openSyntheticEpub(dirPath: String, locatorJson: String?) = Unit
        override fun goToLocator(locatorJson: String) = Unit
        override fun snapshotLocatorJson(): String? = null
        override fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?) = Unit
        override fun setPageLoadCallback(callback: (() -> Unit)?) = Unit
        override fun setTapCallback(callback: ((TapCoords) -> Unit)?) = Unit
        override fun setErrorCallback(callback: ((message: String) -> Unit)?) = Unit
        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) = Unit
        override fun clearSelection() = Unit
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) = Unit
        override fun observeDecorationGroup(group: String) = Unit
        override fun readResource(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) = onResult(null)
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(false)
        override fun startSearch(query: String, onBatch: ((String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun setFootnoteCallback(callback: ((String) -> Unit)?) = Unit
        override fun setRiffleUrlCallback(callback: ((String) -> Unit)?) = Unit
        override fun setInternalLinkCallback(callback: ((String, String) -> Unit)?) = Unit
        override fun setScrollStartCallback(callback: (() -> Unit)?) = Unit
    }

    @Test
    fun pageByForward_callsGoForwardInstant_notGoForward() = runTest {
        val bridge = RecordingBridge()
        val nav = ReadiumSwiftNavigator(bridge, RecordingLogger())

        nav.pageBy(NavigatorPageDirection.Forward)

        assertEquals(1, bridge.goForwardInstantCalls)
        assertEquals(0, bridge.goForwardCalls, "pageBy must not call the animated goForward")
    }

    @Test
    fun pageByBackward_callsGoBackwardInstant_notGoBackward() = runTest {
        val bridge = RecordingBridge()
        val nav = ReadiumSwiftNavigator(bridge, RecordingLogger())

        nav.pageBy(NavigatorPageDirection.Backward)

        assertEquals(1, bridge.goBackwardInstantCalls)
        assertEquals(0, bridge.goBackwardCalls, "pageBy must not call the animated goBackward")
    }
}
