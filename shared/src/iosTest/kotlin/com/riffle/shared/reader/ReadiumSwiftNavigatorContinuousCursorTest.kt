package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorScrollBoundary
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the continuous-mode chapter-map cursor.
 *
 * Root cause: [ReadiumSwiftNavigator.isNativeContinuousActive] was never set to `true`, so
 * [ReadiumSwiftNavigator.scrollBoundaryAndProgression] always ran a JS probe on Readium's
 * own navigator WebView instead of returning the native scroll view's position. The JS probe
 * result was wrong (or zero) in continuous mode, so [continuousCursorOverride] was pinned
 * near zero and masked the correct [chapterMap.cursorPosition].
 *
 * Fix: [ReadiumSwiftNavigator.applyReaderPreferences] now sets [isNativeContinuousActive]
 * from [isContinuousMode]. These tests would fail if the assignment were removed.
 */
class ReadiumSwiftNavigatorContinuousCursorTest {

    private class RecordingBridge : IosEpubNavigatorBridge {
        var locatorCallback: ((String) -> Unit)? = null

        override fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?) {
            locatorCallback = callback
        }
        override fun setTapCallback(callback: ((TapCoords) -> Unit)?) = Unit
        override fun setPageLoadCallback(callback: (() -> Unit)?) = Unit
        override fun setErrorCallback(callback: ((message: String) -> Unit)?) = Unit
        override fun setPublicationOpenErrorCallback(callback: ((message: String) -> Unit)?) = Unit
        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) = Unit
        override fun clearSelection() = Unit
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) = Unit
        override fun observeDecorationGroup(group: String) = Unit
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun readResource(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) = onResult(null)
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun setFootnoteCallback(callback: ((String) -> Unit)?) = Unit
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(true)
        override fun startSearch(query: String, onBatch: ((String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
        override fun setRiffleUrlCallback(callback: ((String) -> Unit)?) = Unit
        override fun setInternalLinkCallback(callback: ((String, String) -> Unit)?) = Unit
        override fun setScrollStartCallback(callback: (() -> Unit)?) = Unit
        override fun goForward() = Unit
        override fun goBackward() = Unit
        override fun goForwardInstant() = Unit
        override fun goBackwardInstant() = Unit
        override fun viewController(): UIViewController = UIViewController()
        override fun openEpub(filePath: String, locatorJson: String?) = Unit
        override fun openSyntheticEpub(dirPath: String, locatorJson: String?) = Unit
        override fun goToLocator(locatorJson: String) = Unit
        override fun snapshotLocatorJson(): String? = null
    }

    private fun applyPrefs(nav: ReadiumSwiftNavigator, isContinuousMode: Boolean) {
        nav.applyReaderPreferences(
            fontSizePercent = 1.0f,
            scrollMode = true,
            theme = "light",
            fontFamilyCss = "",
            lineHeightMultiplier = 1.5f,
            pageMargins = 1.0,
            justifyText = false,
            textColorArgb = 0L,
            publisherStyles = false,
            columnCount = 1,
            isContinuousMode = isContinuousMode,
        )
    }

    @Test
    fun applyReaderPreferences_continuousMode_setsNativeContinuousActive() {
        val nav = ReadiumSwiftNavigator(RecordingBridge(), RecordingLogger())
        applyPrefs(nav, isContinuousMode = true)
        assertTrue(nav.isNativeContinuousActive,
            "isNativeContinuousActive must be true in continuous mode so scrollBoundaryAndProgression() " +
                "returns lastPosition.progression instead of running a JS probe on Readium's WebView")
    }

    @Test
    fun applyReaderPreferences_nonContinuousMode_clearsNativeContinuousActive() {
        val nav = ReadiumSwiftNavigator(RecordingBridge(), RecordingLogger())
        applyPrefs(nav, isContinuousMode = true)
        applyPrefs(nav, isContinuousMode = false)
        assertTrue(!nav.isNativeContinuousActive,
            "isNativeContinuousActive must be cleared when leaving continuous mode")
    }

    @Test
    fun scrollBoundaryAndProgression_nativeContinuousWithPosition_returnsLastPosition() = runTest {
        val bridge = RecordingBridge()
        val nav = ReadiumSwiftNavigator(bridge, RecordingLogger())
        applyPrefs(nav, isContinuousMode = true)

        val locatorJson = """{"href":"ch1.xhtml","type":"application/xhtml+xml",""" +
            """"locations":{"progression":0.42,"totalProgression":0.21,"position":null},"text":{}}"""
        bridge.locatorCallback?.invoke(locatorJson)

        val (boundary, progression) = nav.scrollBoundaryAndProgression()
        assertEquals(NavigatorScrollBoundary.None, boundary,
            "boundary must be None in native continuous mode so chapter transitions are suppressed")
        assertEquals(0.42f, progression, absoluteTolerance = 0.001f,
            "progression must equal lastPosition.progression so continuousCursorOverride tracks the native scroll view")
    }
}
