package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-platform (commonTest) tests for [ColumnSnap] JS builders and pure helpers.
 *
 * These run on both JVM (:feature:reader:jvmTest) and iOS (:feature:reader:iosSimulatorArm64Test)
 * to ensure the shared logic is correct on all KMP targets. The companion Android instrumented
 * tests ([AutoFollowJsTest], [NarratedColumnsJsTest]) exercise the actual JS in a real WebView;
 * these tests only pin the JS string contract (shape, key substrings, quoting correctness) and
 * the pure-Kotlin helpers (parseNarratedColumnsResult, navTargetFragmentId).
 *
 * Mirrors the coverage in the Android-only NarratedColumnsResultParserTest and the
 * ColumnSnap-related subset of ReaderWebViewScriptsTest.
 */
class ColumnSnapJsBuilderTest {

    // ---- parseNarratedColumnsResult ----

    @Test
    fun nullEvaluateResult_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult(null))
    }

    @Test
    fun offSentinel_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"off\""))
    }

    @Test
    fun scrollSentinel_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"scroll\""))
    }

    @Test
    fun singleColumnFraction() {
        assertEquals(listOf(1.0), ColumnSnap.parseNarratedColumnsResult("\"[1.0]\""))
    }

    @Test
    fun twoColumnFractions() {
        assertEquals(listOf(0.6, 1.0), ColumnSnap.parseNarratedColumnsResult("\"[0.6, 1.0]\""))
    }

    @Test
    fun threeColumnFractions_preservesOrder() {
        assertEquals(
            listOf(0.25, 0.6, 1.0),
            ColumnSnap.parseNarratedColumnsResult("\"[0.25, 0.6, 1.0]\""),
        )
    }

    @Test
    fun emptyJsonArray_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"[]\""))
    }

    @Test
    fun integerValuesAreCoercedToDoubles() {
        assertEquals(listOf(1.0), ColumnSnap.parseNarratedColumnsResult("\"[1]\""))
    }

    @Test
    fun malformedJson_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"[not, valid\""))
    }

    @Test
    fun unexpectedSentinel_returnsEmpty() {
        assertEquals(emptyList<Double>(), ColumnSnap.parseNarratedColumnsResult("\"something_else\""))
    }

    @Test
    fun whitespaceAroundResultIsTrimmed() {
        assertEquals(listOf(0.5, 1.0), ColumnSnap.parseNarratedColumnsResult("\"  [0.5, 1.0]  \""))
    }

    // ---- navTargetFragmentId ----

    @Test
    fun noFragment_returnsNull() {
        assertNull(ColumnSnap.navTargetFragmentId("https://example.com/book/ch1.xhtml"))
    }

    @Test
    fun plainFragment_returned() {
        assertEquals("section-1", ColumnSnap.navTargetFragmentId("ch1.xhtml#section-1"))
    }

    @Test
    fun percentEncodedFragment_decodesSpace() {
        assertEquals("section 1", ColumnSnap.navTargetFragmentId("ch1.xhtml#section%201"))
    }

    @Test
    fun emptyFragment_returnsNull() {
        assertNull(ColumnSnap.navTargetFragmentId("ch1.xhtml#"))
    }

    // ---- JS builder shape tests ----

    @Test
    fun measureCadenceColumnsJs_uses_getElementById_and_not_text_search() {
        val js = ColumnSnap.measureCadenceColumnsJs("cd-181")
        assertTrue(js.contains("document.getElementById(\"cd-181\")"), "must resolve target by getElementById")
        assertFalse(js.contains("nodeValue.indexOf(key)"), "must NOT do a text prefix search on the DOM")
    }

    @Test
    fun snapCadenceColumnJs_uses_getElementById_and_reads_column_index() {
        val js = ColumnSnap.snapCadenceColumnJs("cd-181", 2)
        assertTrue(js.contains("document.getElementById(\"cd-181\")"))
        assertTrue(js.contains("var idx=2"))
        assertFalse(js.contains("nodeValue.indexOf(key)"))
    }

    @Test
    fun autoFollowSnapJs_quotesSentenceTextSafely() {
        // Text with inner double-quotes must be JSON-escaped so the JS doesn't break.
        val text = """She said "hello" to me"""
        val js = ColumnSnap.autoFollowSnapJs(text)
        assertTrue(js.contains("\\\"hello\\\""), "double-quotes in sentence text must be JSON-escaped")
        assertFalse(js.contains("var raw=\"She said \"hello\""), "unescaped inner quotes would break JS")
    }

    @Test
    fun autoFollowSnapJs_guards_createTreeWalker_against_null_body() {
        val js = ColumnSnap.autoFollowSnapJs("Hello world.")
        val guardIdx = js.indexOf("if(!document.body) return \"off\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue(guardIdx >= 0, "guard present")
        assertTrue(walkerIdx >= 0, "walker present")
        assertTrue(guardIdx < walkerIdx, "guard must sit BEFORE the walker call")
    }

    @Test
    fun measureNarratedColumnsJs_guards_createTreeWalker_against_null_body() {
        val js = ColumnSnap.measureNarratedColumnsJs("Hello world.")
        val guardIdx = js.indexOf("if(!document.body) return \"off\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue(guardIdx >= 0, "guard present")
        assertTrue(walkerIdx >= 0, "walker present")
        assertTrue(guardIdx < walkerIdx, "guard must sit BEFORE the walker call")
    }

    @Test
    fun scrollToColumnJs_quotesDottedId() {
        assertTrue(
            ColumnSnap.scrollToColumnJs("ftn.ch01fn01").contains("getElementById(\"ftn.ch01fn01\")"),
            "dotted ids must survive verbatim so getElementById matches them",
        )
    }

    @Test
    fun scrollToColumnJs_reportsMoved_same_absent() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        assertTrue(js.contains("return 'absent'"))
        assertTrue(js.contains("var before=se.scrollLeft"))
        assertTrue(js.contains("'moved':'same'"))
    }

    @Test
    fun snapToTargetColumnJs_targetsResourceStartWhenNoFragment() {
        val js = ColumnSnap.snapToTargetColumnJs(null)
        assertTrue(js.contains("var id=null"))
        assertTrue(js.contains("se.scrollLeft=0"))
    }

    @Test
    fun snapToTargetColumnJs_preservesDottedIds() {
        assertTrue(ColumnSnap.snapToTargetColumnJs("ftn.ch01fn01").contains("var id=\"ftn.ch01fn01\""))
    }

    @Test
    fun snapToTargetColumnJs_setsSkipV_true_for_annotationFocus() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = false, locatorProgression = 0.42)
        assertTrue(js.contains("var _skipV=true;"))
        assertTrue(js.contains("if(!_skipV && se && se.scrollHeight > window.innerHeight + 4)"))
    }

    @Test
    fun snapToTargetColumnJs_setsSkipV_false_for_tocNavigation() {
        val js = ColumnSnap.snapToTargetColumnJs("ch01", landAtStartWhenNoTarget = true, locatorProgression = null)
        assertTrue(js.contains("var _skipV=false;"))
        assertTrue(js.contains("if(!_skipV && se && se.scrollHeight > window.innerHeight + 4)"))
    }

    @Test
    fun capturePageFragmentAnchorJs_returnsNull_when_not_paginated() {
        val js = ColumnSnap.CAPTURE_PAGE_FRAGMENT_ANCHOR_JS
        assertTrue(
            js.contains("scrollHeight>window.innerHeight"),
            "guard must return null when content overflows vertically (non-paginated mode)",
        )
        assertFalse(
            js.contains("scrollHeight<=window.innerHeight"),
            "guard must NOT use <= (that would be a no-op in paginated mode)",
        )
    }

    @Test
    fun capturePageFragmentAnchorJs_prefersParagraphElementsOverSectionContainers() {
        val js = ColumnSnap.CAPTURE_PAGE_FRAGMENT_ANCHOR_JS
        val pIdx = js.indexOf("p[id]")
        val sectionIdx = js.indexOf("section[id]")
        assertTrue(pIdx in 0 until sectionIdx, "p[id] must be queried before section[id]")
    }

    @Test
    fun settleSnapInstallJs_usesShortDebounce_during_activeSelection() {
        val js = ColumnSnap.SETTLE_SNAP_INSTALL_JS
        assertTrue(js.contains("getSelection") && js.contains("isCollapsed"), "must check getSelection().isCollapsed")
        assertTrue(js.contains("delay=32"), "must set delay=32 during non-collapsed selection")
        assertTrue(js.contains("delay = 120") || js.contains("delay=120"), "must keep delay=120 for normal scroll events")
        val defaultDelayIdx = js.indexOf("delay = 120").let { if (it < 0) js.indexOf("delay=120") else it }
        val shortDelayIdx = js.indexOf("delay=32")
        assertTrue(shortDelayIdx > defaultDelayIdx, "delay=32 override must appear AFTER the default delay=120")
    }

    @Test
    fun stashVerticalOriginJs_capturesScrollTop_and_fragmentStrippedHref() {
        val js = ColumnSnap.STASH_VERTICAL_ORIGIN_JS
        assertTrue(js.contains("window.__riffleOriginY=se.scrollTop"), "must stash scrollTop")
        assertTrue(
            js.contains("window.__riffleOriginHref=location.href.split('#')[0]"),
            "must stash fragment-stripped href",
        )
    }
}
