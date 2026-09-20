package com.riffle.shared.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.RecordingLogger
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override fun setTapCallback(callback: (() -> Unit)?) = Unit
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun startSearch(query: String, onBatch: ((matchesJson: String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
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
}
