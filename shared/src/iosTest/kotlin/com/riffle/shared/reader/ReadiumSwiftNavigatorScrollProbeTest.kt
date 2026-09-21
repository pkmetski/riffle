package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorScrollBoundary
import com.riffle.feature.reader.ScrollProbes
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two seams that made Continuous mode impossible on iOS.
 *
 * `scrollBoundary()` answered [NavigatorScrollBoundary.None] unconditionally and
 * `viewportFractionEvents` was `emptyFlow()`, so nothing on iOS could tell that the reader had
 * arrived at the end of a resource and nothing could measure how much of a chapter one screen
 * covers. These pin the Kotlin half — that the right script goes out and the answer comes back
 * shaped the way the shared consumers need it. The *JavaScript* half, that the script reads a
 * real WKWebView's real scroll state, is `ScrollProbeBridgeTests.swift` against a live
 * `EPUBNavigatorViewController`.
 */
class ReadiumSwiftNavigatorScrollProbeTest {

    private class FakeBridge(
        /** Answers keyed by the script that was evaluated. */
        var answers: (String) -> String? = { null },
    ) : IosEpubNavigatorBridge {
        val evaluated = mutableListOf<String>()

        override fun viewController(): UIViewController = UIViewController()
        override fun openEpub(filePath: String, locatorJson: String?) = Unit
        override fun goForward() = Unit
        override fun goBackward() = Unit
        override fun goToLocator(locatorJson: String) = Unit
        override fun snapshotLocatorJson(): String? = null
        override fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?) = Unit
        override fun setPageLoadCallback(callback: (() -> Unit)?) = Unit
        override fun setTapCallback(callback: (() -> Unit)?) = Unit
        override fun setErrorCallback(callback: ((message: String) -> Unit)?) = Unit
        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) = Unit
        override fun clearSelection() = Unit
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) = Unit
        override fun observeDecorationGroup(group: String) = Unit
        override fun readResource(href: String, onResult: (String?) -> Unit) = onResult(null)
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(false)
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) {
            evaluated += script
            onResult(answers(script))
        }
        override fun startSearch(query: String, onBatch: ((matchesJson: String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
    }

    private fun navigator(bridge: FakeBridge) = ReadiumSwiftNavigator(bridge, RecordingLogger())

    @Test
    fun scrollBoundaryReportsWhatTheDocumentSays() = runTest {
        val bridge = FakeBridge(answers = { "true,false" })
        assertEquals(
            NavigatorScrollBoundary(atForwardBoundary = true, atBackwardBoundary = false),
            navigator(bridge).scrollBoundary(),
            "the navigator must report the probe's answer; a hardcoded None is what made " +
                "Continuous mode impossible",
        )
        assertEquals(
            listOf(ScrollProbes.BOUNDARY_PROBE_JS),
            bridge.evaluated,
            "the boundary must be read with the shared probe, in ONE round trip — two hops can " +
                "straddle a scroll frame and report the top and the bottom of two different " +
                "scroll positions",
        )
    }

    @Test
    fun scrollBoundaryReportsTheTopOfAResource() = runTest {
        val bridge = FakeBridge(answers = { "false,true" })
        assertEquals(
            NavigatorScrollBoundary(atForwardBoundary = false, atBackwardBoundary = true),
            navigator(bridge).scrollBoundary(),
        )
    }

    @Test
    fun scrollBoundaryIsNoneWhenThereIsNoNavigator() = runTest {
        // A closed navigator answers null to every script; "no boundary" is the only safe
        // reading, because it is the one that never fires a navigation.
        val bridge = FakeBridge(answers = { null })
        assertEquals(NavigatorScrollBoundary.None, navigator(bridge).scrollBoundary())
    }

    @Test
    fun viewportFractionIsPublishedAgainstTheHrefItWasMeasuredFor() = runTest {
        val bridge = FakeBridge(answers = { "0.25" })
        val nav = navigator(bridge)
        val received = mutableListOf<Pair<String, Double>>()
        val job = launch { nav.viewportFractionEvents.collect { received += it } }
        // Give the collector a chance to subscribe before the (non-replaying) emission.
        yield()
        nav.publishViewportFraction("OEBPS/ch1.xhtml")
        yield()
        job.cancel()

        assertEquals(listOf("OEBPS/ch1.xhtml" to 0.25), received)
        assertTrue(bridge.evaluated.contains(ScrollProbes.VIEWPORT_FRACTION_JS))
    }

    @Test
    fun anUnusableMeasurementIsNotPublished() = runTest {
        // Publishing a zero would poison `bookmarkEpsFor`'s highest-priority branch with a
        // window of nothing, and the corner ribbon would never light again for that chapter.
        val bridge = FakeBridge(answers = { "" })
        val nav = navigator(bridge)
        var emissions = 0
        val job = launch { nav.viewportFractionEvents.collect { emissions++ } }
        yield()
        nav.publishViewportFraction("OEBPS/ch1.xhtml")
        yield()
        job.cancel()
        assertEquals(0, emissions)
    }

    @Test
    fun anEmptyHrefIsNeverProbed() = runTest {
        val bridge = FakeBridge(answers = { "0.25" })
        navigator(bridge).publishViewportFraction("")
        assertEquals(emptyList(), bridge.evaluated)
    }
}
