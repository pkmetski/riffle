package com.riffle.shared.reader

import com.riffle.core.logging.RecordingLogger
import com.riffle.core.models.EmphasisStyle
import com.riffle.feature.reader.NavigatorDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIViewController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin half of the annotation bridge seam: the selection and decoration-activation
 * payloads Swift sends, the decoration payloads Kotlin sends back, and the chapter-source read
 * the shared annotation domain runs on.
 *
 * Every one of these was absent before — `IosEpubNavigatorBridge` had no selection callback, no
 * activation callback and no resource read, which is the structural reason no annotation could
 * be created, edited or tapped on iOS.
 */
class ReadiumSwiftNavigatorAnnotationTest {

    private class FakeBridge : IosEpubNavigatorBridge {
        var selectionCallback: ((String?) -> Unit)? = null
        var decorationCallback: ((String) -> Unit)? = null
        val observedGroups = mutableListOf<String>()
        var clearSelectionCalls = 0
        var resources: Map<String, String> = emptyMap()
        val appliedByGroup = mutableMapOf<String, String>()

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
        override fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?) {
            selectionCallback = callback
        }
        override fun clearSelection() {
            clearSelectionCalls++
        }
        override fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?) {
            decorationCallback = callback
        }
        override fun observeDecorationGroup(group: String) {
            observedGroups += group
        }
        override fun readResource(href: String, onResult: (String?) -> Unit) {
            onResult(resources[href])
        }
        override fun disposeNavigator() = Unit
        override fun applyDecorations(decorationsJson: String, group: String) {
            appliedByGroup[group] = decorationsJson
        }
        override fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher) = Unit
        override fun applyReaderPreferences(preferences: IosReaderPreferences) = Unit
        override fun getTocJson(): String = "[]"
        override fun getSpineJson(): String = """{"hrefs":[],"positionCounts":[]}"""
        override fun scrollByPx(pixels: Int, onResult: (Boolean) -> Unit) = onResult(false)
        override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) = onResult(null)
        override fun startSearch(query: String, onBatch: ((matchesJson: String) -> Unit)?, onDone: (() -> Unit)?) = Unit
        override fun cancelSearch() = Unit
        override fun setFigureTapCallback(callback: ((String) -> Unit)?) = Unit
        override fun readResourceBase64(href: String, onResult: (String?) -> Unit) = onResult(null)
    }

    private fun navigator(bridge: FakeBridge) = ReadiumSwiftNavigator(bridge, RecordingLogger())

    private val selectionPayload = """
        {"locatorJson":"{\"href\":\"ch1.xhtml\"}","href":"ch1.xhtml","text":"the selected words",
         "before":"context ","after":" after","progression":0.42,
         "x":10.0,"y":320.0,"width":180.0,"height":22.0}
    """.trimIndent()

    // ── Selection ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aSelectionReachesTheReaderWithItsTextTripleAndItsRect() {
        val bridge = FakeBridge()
        val navigator = navigator(bridge)

        assertNull(navigator.selectionFlow.value, "no selection before Readium reports one")
        bridge.selectionCallback?.invoke(selectionPayload)

        val selection = navigator.selectionFlow.value
        assertNotNull(selection, "the selection callback must populate the flow the sheet keys on")
        assertEquals("ch1.xhtml", selection.href)
        // The text triple is the whole annotation domain's anchor; losing `before`/`after`
        // makes a repeated phrase resolve to the wrong occurrence.
        assertEquals("the selected words", selection.text)
        assertEquals("context ", selection.before)
        assertEquals(" after", selection.after)
        assertEquals(0.42, selection.progression)
        assertEquals(10f, selection.rect?.x)
        assertEquals(22f, selection.rect?.height)
    }

    @Test
    fun aClearedSelectionEmptiesTheFlowSoTheSheetDismisses() {
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        bridge.selectionCallback?.invoke(selectionPayload)
        assertNotNull(navigator.selectionFlow.value)

        bridge.selectionCallback?.invoke(null)
        assertNull(navigator.selectionFlow.value)
    }

    @Test
    fun aWhitespaceOnlySelectionIsNotTreatedAsOne() {
        // A stray double-tap in a margin reports an empty selection; popping the annotate sheet
        // over nothing is worse than ignoring it.
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        bridge.selectionCallback?.invoke("""{"locatorJson":"{}","href":"ch1.xhtml","text":"  "}""")
        assertNull(navigator.selectionFlow.value)
    }

    @Test
    fun clearingTheSelectionAlsoTellsReadiumToDropIt() {
        // Otherwise the native Copy menu stays up over a highlight the user has already made.
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        bridge.selectionCallback?.invoke(selectionPayload)

        navigator.clearSelection()

        assertEquals(1, bridge.clearSelectionCalls)
        assertNull(navigator.selectionFlow.value)
    }

    // ── Decoration activation ───────────────────────────────────────────────────────────────

    @Test
    fun tappingADecorationReportsItsAnnotationIdAndGroup() = runTest {
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        val seen = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            navigator.decorationActivations.collect { seen += "${it.group}:${it.id}:${it.rect?.y}" }
        }

        bridge.decorationCallback?.invoke(
            """{"id":"ann-7","group":"highlights","x":4.0,"y":120.0,"width":200.0,"height":18.0}""",
        )

        assertEquals(listOf("highlights:ann-7:120.0"), seen)
        job.cancel()
    }

    @Test
    fun anActivationWithNoRectIsStillDelivered() {
        // Readium reports a null rect for a decoration whose range is off-screen; dropping the
        // event would make the tap do nothing at all.
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        val parsed = navigator.parseActivationJson("""{"id":"ann-1","group":"annotation-notes"}""")
        assertNotNull(parsed)
        assertNull(parsed.rect)
        assertEquals("annotation-notes", parsed.group)
    }

    @Test
    fun malformedPayloadsAreDroppedRatherThanCrashingTheReader() {
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        assertNull(navigator.parseActivationJson("not json"))
        assertNull(navigator.parseActivationJson("""{"group":"highlights"}"""))
        assertNull(navigator.parseSelectionJson(""))
    }

    // ── Decoration groups ───────────────────────────────────────────────────────────────────

    @Test
    fun onlyTheGroupsThatShouldBeTappableAreRegistered() {
        // Readium's `findDecorationTarget` skips every group that was not registered, so a
        // highlight in an unregistered group renders and swallows nothing.
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        ReaderDecorationGroups.activable.forEach { navigator.observeDecorationGroup(it) }

        assertEquals(
            listOf(
                ReaderDecorationGroups.highlights,
                ReaderDecorationGroups.bookmarks,
                ReaderDecorationGroups.noteGlyphs,
            ),
            bridge.observedGroups,
        )
        assertFalse(
            ReaderDecorationGroups.cadence in bridge.observedGroups,
            "the cadence sentence is not a tap target",
        )
    }

    // ── Emphasis serialisation ──────────────────────────────────────────────────────────────

    @Test
    fun emphasisDecorationsCarryTheirStyleTokensAcrossTheBridge() {
        val bridge = FakeBridge()
        val navigator = navigator(bridge)
        navigator.applyDecorations(
            ReaderDecorationGroups.emphasis,
            listOf(
                NavigatorDecoration.Emphasis(
                    id = "em-1",
                    locatorJson = """{"href":"ch1.xhtml"}""",
                    styles = setOf(EmphasisStyle.UNDERLINE, EmphasisStyle.STRIKE),
                ),
            ),
        )
        val json = bridge.appliedByGroup[ReaderDecorationGroups.emphasis] ?: ""
        assertTrue(json.contains("\"type\":\"emphasis\""), json)
        assertTrue(json.contains("underline"), json)
        assertTrue(json.contains("strike"), json)
    }

    // ── Chapter source ──────────────────────────────────────────────────────────────────────

    @Test
    fun theChapterSourceComesFromThePublicationNotTheLiveDom() = runTest {
        // Every merge / CFI decision is computed against these bytes. Reading them back out of
        // the WKWebView would include Readium's injected scripts and Cadence's sentence spans,
        // and the character offsets would no longer match Android's for the same book.
        val bridge = FakeBridge()
        bridge.resources = mapOf("ch1.xhtml" to "<html><body><p>Hello</p></body></html>")
        val navigator = navigator(bridge)

        assertEquals("<html><body><p>Hello</p></body></html>", navigator.readChapterHtml("ch1.xhtml"))
        assertNull(navigator.readChapterHtml("missing.xhtml"))
        assertEquals(
            "<html><body><p>Hello</p></body></html>",
            navigator.getChapterBytes("ch1.xhtml")?.decodeToString(),
        )
    }
}
