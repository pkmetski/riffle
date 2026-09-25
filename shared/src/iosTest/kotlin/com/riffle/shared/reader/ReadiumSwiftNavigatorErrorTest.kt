package com.riffle.shared.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.RecordingLogger
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1071 §17 — `ReadiumEpubNavigatorBridge.presentError` was an empty Swift body, so every error
 * Readium reported about the open publication (`copyForbidden`, resource failures) vanished with
 * no trace in logs, no crash report and nothing on screen.
 *
 * The Swift delegate now forwards the description to the bridge's error callback, and
 * [ReadiumSwiftNavigator] logs it on [LogChannel.Reader]. Reverting either half — deleting the
 * `bridge.setErrorCallback { … }` registration, or emptying `presentError` again — leaves
 * `logger.errors` empty and fails [logsNavigatorErrorsReportedByReadium].
 */
class ReadiumSwiftNavigatorErrorTest {

    private class RecordingBridge : IosEpubNavigatorBridge {
        var errorCallback: ((String) -> Unit)? = null

        override fun setErrorCallback(callback: ((message: String) -> Unit)?) {
            errorCallback = callback
        }

        override fun viewController(): UIViewController = UIViewController()
        override fun openEpub(filePath: String, locatorJson: String?) = Unit
        override fun goForward() = Unit
        override fun goBackward() = Unit
        override fun goToLocator(locatorJson: String) = Unit
        override fun snapshotLocatorJson(): String? = null
        override fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?) = Unit
        override fun setPageLoadCallback(callback: (() -> Unit)?) = Unit
        override fun setTapCallback(callback: ((TapCoords) -> Unit)?) = Unit
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""

        var scrolledBy: Int? = null
        var scrollMoves = true
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) {
            scrolledBy = pixels
            onResult(scrollMoves)
        }
        override fun startSearch(query: String, onBatch: ((matchesJson: String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit

        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) {
            selectionCallback = callback
        }
        var selectionCallback: ((String?) -> Unit)? = null
        var clearSelectionCalls = 0
        override fun clearSelection() {
            clearSelectionCalls++
        }
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) {
            decorationCallback = callback
        }
        var decorationCallback: ((String) -> Unit)? = null
        val observedGroups = mutableListOf<String>()
        override fun observeDecorationGroup(group: String) {
            observedGroups += group
        }
        var resources: Map<String, String> = emptyMap()
        override fun readResource(href: String, onResult: (String?) -> Unit) {
            onResult(resources[href])
        }

        /** Scripts handed to the WebView, newest last, and the canned answer for each. */
        val evaluated = mutableListOf<String>()
        var jsResult: String? = null
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) {
            evaluated += script
            onResult(jsResult)
        }
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
    }

    @Test
    fun logsNavigatorErrorsReportedByReadium() {
        val bridge = RecordingBridge()
        val logger = RecordingLogger()

        ReadiumSwiftNavigator(bridge, logger)
        bridge.errorCallback?.invoke("copyForbidden")

        val errors = logger.records(LogChannel.Reader).filter { it.level == RecordingLogger.Level.E }
        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("copyForbidden"))
    }

    @Test
    fun clearsTheErrorCallbackOnClose() {
        val bridge = RecordingBridge()

        ReadiumSwiftNavigator(bridge, RecordingLogger()).close()

        assertNull(bridge.errorCallback)
    }

    /**
     * Auto-scroll's only output is a stream of pixel deltas; this is the seam that turns one into
     * a scroll. The return value is what tells the ticker it has hit the bottom of the resource —
     * swallowing it (always returning true) would leave auto-scroll spinning against a document
     * that cannot move, which is the failure Android's vertical mode guards against too.
     */
    @Test
    fun scrollByPxForwardsTheDeltaAndReportsWhetherTheDocumentMoved() = runTest {
        val bridge = RecordingBridge()
        val navigator = ReadiumSwiftNavigator(bridge, RecordingLogger())

        assertTrue(navigator.scrollByPx(7))
        assertEquals(7, bridge.scrolledBy)

        bridge.scrollMoves = false
        assertFalse(navigator.scrollByPx(3))
        assertEquals(3, bridge.scrolledBy)
    }
}
