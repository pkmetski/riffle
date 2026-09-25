package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.cadence.CadenceInjector
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin half of iOS's Cadence pipeline: turning a JavaScript answer into the typed result
 * the reader acts on.
 *
 * These four methods were `Unavailable` / `emptyList()` / `{}` stubs. A stub compiles, satisfies
 * the interface and leaves the reader with no highlight and no page turn — so what has to be
 * pinned is not "the method exists" but "the WebView's answer is interpreted correctly". The
 * other half, that the scripts actually change Readium's document, is `CadenceBridgeTests.swift`
 * against a real `EPUBNavigatorViewController`.
 */
class ReadiumSwiftNavigatorCadenceTest {

    private class ScriptBridge : IosEpubNavigatorBridge {
        val evaluated = mutableListOf<String>()
        var answer: String? = null

        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) {
            evaluated += script
            onResult(answer)
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
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) = Unit
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(false)
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
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
    }

    private fun navigator(bridge: ScriptBridge) = ReadiumSwiftNavigator(bridge, RecordingLogger())

    // ── followCadenceSpan ──────────────────────────────────────────────────────────────────────

    /**
     * "same" must NOT collapse into [NavigatorFollowResult.OffPage]: the follow runs once per
     * sentence, and OffPage makes the caller navigate, so collapsing it would fire a chapter
     * navigation on every tick while the sentence sits comfortably visible.
     */
    @Test
    fun followReportsSnappedForBothMovedAndAlreadyOnPage() = runTest {
        val bridge = ScriptBridge()
        val nav = navigator(bridge)

        bridge.answer = "moved"
        assertEquals(NavigatorFollowResult.Snapped, nav.followCadenceSpan("cd-3"))

        bridge.answer = "same"
        assertEquals(NavigatorFollowResult.Snapped, nav.followCadenceSpan("cd-3"))
    }

    @Test
    fun followReportsOffPageWhenTheSpanIsNotInThisResource() = runTest {
        val bridge = ScriptBridge().apply { answer = "absent" }
        assertEquals(NavigatorFollowResult.OffPage, navigator(bridge).followCadenceSpan("cd-3"))
    }

    @Test
    fun followTolerateAJsonQuotedAnswerAndAMissingOne() = runTest {
        val bridge = ScriptBridge().apply { answer = "\"moved\"" }
        assertEquals(NavigatorFollowResult.Snapped, navigator(bridge).followCadenceSpan("cd-3"))

        val silent = ScriptBridge()
        assertEquals(NavigatorFollowResult.Unavailable, navigator(silent).followCadenceSpan("cd-3"))
    }

    @Test
    fun followTargetsTheSpanByIdNotByItsText() = runTest {
        val bridge = ScriptBridge().apply { answer = "moved" }
        navigator(bridge).followCadenceSpan("cd-42")
        // getElementById is what makes Cadence immune to the "text search hits an earlier
        // occurrence" bug Readaloud's text-anchored follow has to live with.
        assertTrue(bridge.evaluated.single().contains("getElementById(\"cd-42\")"))
    }

    // ── measure / snap ─────────────────────────────────────────────────────────────────────────

    @Test
    fun measureParsesTheCumulativeColumnFractions() = runTest {
        val bridge = ScriptBridge().apply { answer = "[0.6,1.0]" }
        assertEquals(listOf(0.6, 1.0), navigator(bridge).measureCadenceColumns("cd-1"))
    }

    /**
     * "scroll" is the shared JS's answer in Readium's scroll mode — which is where BOTH of iOS's
     * non-paginated reading modes land. An empty list is what tells
     * `NarratedColumnProgression` never to advance, i.e. never to turn a page in a document that
     * has no column grid.
     */
    @Test
    fun measureIsEmptyInScrollModeAndWhenTheSpanIsAbsent() = runTest {
        assertEquals(emptyList(), navigator(ScriptBridge().apply { answer = "scroll" }).measureCadenceColumns("cd-1"))
        assertEquals(emptyList(), navigator(ScriptBridge().apply { answer = "off" }).measureCadenceColumns("cd-1"))
        assertEquals(emptyList(), navigator(ScriptBridge()).measureCadenceColumns("cd-1"))
    }

    @Test
    fun snapAsksForTheRequestedColumnOfTheRequestedSpan() = runTest {
        val bridge = ScriptBridge()
        navigator(bridge).snapCadenceColumn("cd-9", columnIndex = 2)
        val js = bridge.evaluated.single()
        assertTrue(js.contains("getElementById(\"cd-9\")"))
        assertTrue(js.contains("var idx=2;"))
    }

    // ── feature detect + tokenise + start probe ────────────────────────────────────────────────

    @Test
    fun featureDetectReadsBothTheBareAndTheQuotedBoolean() = runTest {
        assertEquals(true, navigator(ScriptBridge().apply { answer = "true" }).cadenceFeatureDetect())
        assertEquals(false, navigator(ScriptBridge().apply { answer = "\"false\"" }).cadenceFeatureDetect())
        // An unanswered probe is "don't know", not "unsupported" — reporting false would persist
        // `cadencePlatformSupported = false` and hide the Settings row on a capable device.
        assertNull(navigator(ScriptBridge()).cadenceFeatureDetect())
    }

    @Test
    fun tokeniseTurnsTheScriptsJsonIntoQuotesKeyedByFragmentRef() = runTest {
        val bridge = ScriptBridge().apply {
            answer = """{"quotes":{"c1.xhtml#cd-0":{"before":"","highlight":"Hello.","after":""}},""" +
                """"chapterHrefs":{"c1.xhtml#cd-0":"c1.xhtml"},"supported":true}"""
        }
        val result = navigator(bridge).cadenceTokeniseChapter("c1.xhtml", null)
        val ready = result as CadenceInjector.Result.Ready
        assertEquals("Hello.", ready.quotes.getValue("c1.xhtml#cd-0").highlight)
        assertEquals("c1.xhtml", ready.chapterHrefs.getValue("c1.xhtml#cd-0"))
    }

    @Test
    fun tokeniseReportsUnsupportedRatherThanThrowingOnRubbish() = runTest {
        assertEquals(
            CadenceInjector.Result.Unsupported,
            navigator(ScriptBridge().apply { answer = "not json" }).cadenceTokeniseChapter("c1.xhtml", null),
        )
        assertEquals(
            CadenceInjector.Result.Unsupported,
            navigator(ScriptBridge()).cadenceTokeniseChapter("c1.xhtml", null),
        )
    }

    /**
     * The probe's payload carries the chapter the DOM was tokenised for, and the ref is built
     * from THAT rather than from Readium's locator href — the locator can lag the rendered DOM
     * by one chapter after a paginated turn, which files the start ref under the wrong chapter
     * and drops playback on the first sentence of the book.
     */
    @Test
    fun theStartProbeReturnsAChapterQualifiedRef() = runTest {
        val bridge = ScriptBridge().apply { answer = """{"id":"cd-12","chapter":"c3.xhtml","rule":1}""" }
        assertEquals("c3.xhtml#cd-12", navigator(bridge).cadenceStartSpanId())
    }

    @Test
    fun theStartProbeReturnsNullWhenNoSentenceCouldBeLocated() = runTest {
        assertNull(navigator(ScriptBridge().apply { answer = """{"id":"","rule":0}""" }).cadenceStartSpanId())
        assertNull(navigator(ScriptBridge()).cadenceStartSpanId())
    }
}
