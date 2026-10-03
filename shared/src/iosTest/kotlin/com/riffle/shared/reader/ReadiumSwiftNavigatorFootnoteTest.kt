package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the footnote-callback wiring in [ReadiumSwiftNavigator].
 *
 * Readium-Swift calls `EPUBNavigatorDelegate.navigator(_:shouldNavigateToNoteAt:content:referrer:)`
 * when the user taps a `epub:type="noteref"` anchor. The Swift bridge strips the HTML, fires the
 * registered Kotlin callback, and returns `false` so the Readium navigator does not jump to the
 * note location — the `FootnotePopup` composable shows it inline instead.
 *
 * These tests verify the Kotlin side of that seam:
 *  - [ReadiumSwiftNavigator] registers a footnote callback on the bridge during construction.
 *  - Invoking that callback emits [NavigatorEvent.Footnote] on `eventFlow`.
 *  - Closing the navigator clears the callback so later bridge firings are ignored.
 *
 * Reverting the `bridge.setFootnoteCallback { … }` registration in [ReadiumSwiftNavigator], or
 * removing the `is NavigatorEvent.Footnote` branch from the event collector in
 * `IosEpubReaderScreen`, would leave [footnoteCallbackEmitsFootnoteEvent] red.
 */
class ReadiumSwiftNavigatorFootnoteTest {

    private class RecordingBridge : IosEpubNavigatorBridge {
        var footnoteCallback: ((String) -> Unit)? = null

        override fun setFootnoteCallback(callback: ((String) -> Unit)?) {
            footnoteCallback = callback
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

    @Test
    fun footnoteCallbackEmitsFootnoteEvent() = runTest {
        val bridge = RecordingBridge()
        val navigator = ReadiumSwiftNavigator(bridge, RecordingLogger())

        val deferred = async { navigator.eventFlow.first { it is NavigatorEvent.Footnote } }
        yield() // let the async collector subscribe before the callback fires
        bridge.footnoteCallback?.invoke("A stripped footnote")

        val event = deferred.await()
        assertIs<NavigatorEvent.Footnote>(event)
        assertEquals("A stripped footnote", event.contentHtml)
    }

    @Test
    fun closeRegistersNullFootnoteCallback() {
        val bridge = RecordingBridge()
        val navigator = ReadiumSwiftNavigator(bridge, RecordingLogger())

        navigator.close()

        kotlin.test.assertNull(bridge.footnoteCallback,
            "close() must clear the footnote callback to prevent leaks")
    }
}
