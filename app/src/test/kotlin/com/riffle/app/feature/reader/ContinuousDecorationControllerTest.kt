package com.riffle.app.feature.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousDecorationControllerTest {

    /** Hand-written fake driving [ChapterWebViewLike] without a live Android WebView. Records
     *  every (script, callback) pair passed to [evaluateJavascript] so tests can invoke the
     *  callback synchronously and assert on how many/which scripts were evaluated. */
    private class FakeChapterWebView(override val chapterHref: String) : ChapterWebViewLike {
        val evaluatedJs = mutableListOf<Pair<String, ((String?) -> Unit)?>>()
        val evaluatedJsCount: Int get() = evaluatedJs.size

        override fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?) {
            evaluatedJs += script to resultCallback
        }

        /** Invokes the most recently recorded callback (if any) with [result], simulating the
         *  WebView completing evaluation of the last-issued script. */
        fun completeLast(result: String? = null) {
            evaluatedJs.lastOrNull()?.second?.invoke(result)
        }

        override var onTap: (() -> Unit)? = null
        override var onRenderGone: (() -> Unit)? = null
        override var onInternalLink: ((String) -> Unit)? = null
        override var onExternalLink: ((String) -> Unit)? = null
        override var annotationsAvailable: Boolean = false
        override var readaloudAvailable: Boolean = false
        override var onSelectionActiveChanged: ((Boolean) -> Unit)? = null
        override var onHighlight: ((String, Double, android.graphics.Rect, String, String) -> Unit)? = null
        override var onAnnotationTap: ((String, android.graphics.Rect) -> Unit)? = null
        override var onAnnotationNoteTap: ((String, android.graphics.Rect) -> Unit)? = null
        override var onFootnoteContent: ((FootnoteContent) -> Unit)? = null
        override var onCrossReferenceTap: ((String) -> Unit)? = null
        override var onFigureTap: ((String) -> Unit)? = null
        override var onFigureLongPress: ((FigureLongPressPayload) -> Unit)? = null
    }

    private class FakePort : ContinuousDecorationController.Port {
        val loaded = mutableListOf<FakeChapterWebView>()
        override fun forEachLoadedWebView(block: (ChapterWebViewLike) -> Unit) { loaded.forEach(block) }
        override fun findLoadedWebView(href: String): ChapterWebViewLike? =
            loaded.firstOrNull { it.chapterHref == href }
        override fun scrollTo(y: Int) { lastScrollY = y }
        override fun smoothScrollTo(y: Int) { lastSmoothScrollY = y }
        override fun clearLandingHold() { landingHoldCleared = true }
        override fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot> = window
        override val viewportHeightPx: Int = 1000
        override val currentScrollY: Int get() = scrollY

        var lastScrollY: Int? = null
        var lastSmoothScrollY: Int? = null
        var landingHoldCleared: Boolean = false
        var window: List<ContinuousPositionTracker.ChapterSlot> = emptyList()
        var scrollY: Int = 0
    }

    // ---- annotation state persistence ----------------------------------------

    @Test
    fun `applyAnnotationHighlights persists state and applies to newly loaded chapter via onChapterLoaded`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val state = mapOf(
            "ch-1.xhtml" to listOf(AnnotationHighlight("id1", "text", "rgba(255,0,0,0.4)")),
        )

        c.applyAnnotationHighlights(state)              // no chapter loaded yet — no-op
        assertEquals(0, port.loaded.sumOf { it.evaluatedJsCount })

        val wv = FakeChapterWebView(chapterHref = "ch-1.xhtml").also { port.loaded += it }
        c.onChapterLoaded(wv)                            // simulates onPageFinished re-apply

        assertEquals(1, wv.evaluatedJsCount)             // annotation JS re-applied
    }

    @Test
    fun `applyAnnotationHighlights then clearing state issues clear JS, not apply JS, on next chapter load`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val href = "ch-1.xhtml"
        c.applyAnnotationHighlights(mapOf(href to listOf(AnnotationHighlight("id1", "text", "rgba(0,0,0,1)"))))
        c.applyAnnotationHighlights(emptyMap())          // clears persisted state

        val wv = FakeChapterWebView(chapterHref = href).also { port.loaded += it }
        c.onChapterLoaded(wv)

        // No annotations persisted for this href anymore -> onChapterLoaded issues no annotation JS.
        assertEquals(0, wv.evaluatedJsCount)
    }

    @Test
    fun `onChapterLoaded fires onAnnotationsApplied callback once annotation JS completes`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val href = "ch-1.xhtml"
        c.applyAnnotationHighlights(mapOf(href to listOf(AnnotationHighlight("id1", "text", "rgba(0,0,0,1)"))))
        val wv = FakeChapterWebView(chapterHref = href)

        var applied = false
        c.onChapterLoaded(wv, onAnnotationsApplied = { applied = true })
        assertFalse(applied)                             // JS issued, not yet completed
        wv.completeLast()
        assertTrue(applied)
    }

    // ---- search state persistence + clearing ----------------------------------

    @Test
    fun `applySearchHighlights(null) clears state and issues clear JS to every loaded chapter`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        port.loaded += FakeChapterWebView("ch-1.xhtml")
        port.loaded += FakeChapterWebView("ch-2.xhtml")

        c.applySearchHighlights(SearchHighlightsState(
            resultsByHref = mapOf("ch-1.xhtml" to listOf("foo")),
            activeHref = "ch-1.xhtml",
            activeText = "foo",
            activeProgression = 0.5f,
            activeCssColor = "rgba(1,1,1,1)",
            inactiveCssColor = "rgba(2,2,2,1)",
        ))
        val before = port.loaded.sumOf { it.evaluatedJsCount }
        c.applySearchHighlights(null)
        val after = port.loaded.sumOf { it.evaluatedJsCount }

        assertEquals(2, after - before)                  // one clear per loaded WebView
    }

    @Test
    fun `search state persists across chapter loads and is applied via onChapterLoaded`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        c.applySearchHighlights(SearchHighlightsState(
            resultsByHref = mapOf("ch-1.xhtml" to listOf("foo")),
            activeHref = "ch-1.xhtml",
            activeText = "foo",
            activeProgression = 0.5f,
            activeCssColor = "rgba(1,1,1,1)",
            inactiveCssColor = "rgba(2,2,2,1)",
        ))

        val wv = FakeChapterWebView(chapterHref = "ch-1.xhtml")
        c.onChapterLoaded(wv)

        assertTrue("search JS should be applied for a chapter with pending results", wv.evaluatedJsCount > 0)
    }

    // ---- readaloud scroll-jitter guard -----------------------------------------

    @Test
    fun `highlightInChapter does not scroll when target is within viewportHeightPx div 8 of current scrollY`() {
        val port = FakePort()
        port.window = listOf(ContinuousPositionTracker.ChapterSlot(href = "ch-1.xhtml", top = 0, height = 2000))
        // vh = 1000; target = slot.top + elementTop - vh/3 = 0 + 400 - 333 = 67
        // Set currentScrollY close enough that |target - current| <= vh/8 (125) -> no scroll.
        port.scrollY = 67
        val c = ContinuousDecorationController(port)
        val wv = FakeChapterWebView(chapterHref = "ch-1.xhtml").also { port.loaded += it }

        c.highlightInChapter("ch-1.xhtml", fragmentId = null, "some text", "rgba(0,0,0,1)")
        wv.completeLast() // completes highlightTextJs -> triggers scrollToReadaloudHighlight's evaluateJavascript
        wv.completeLast("400") // completes the position-query JS with elementTop = 400 device px

        assertEquals(null, port.lastScrollY)
        assertEquals(null, port.lastSmoothScrollY)
        assertFalse(port.landingHoldCleared)
    }

    @Test
    fun `highlightInChapter scrolls when target delta exceeds viewportHeightPx div 8`() {
        val port = FakePort()
        port.window = listOf(ContinuousPositionTracker.ChapterSlot(href = "ch-1.xhtml", top = 0, height = 2000))
        // target = 0 + 400 - 333 = 67; put currentScrollY far away so |delta| > 125.
        port.scrollY = 1000
        val c = ContinuousDecorationController(port)
        val wv = FakeChapterWebView(chapterHref = "ch-1.xhtml").also { port.loaded += it }

        c.highlightInChapter("ch-1.xhtml", fragmentId = null, "some text", "rgba(0,0,0,1)")
        wv.completeLast()
        wv.completeLast("400")

        assertEquals(67, port.lastSmoothScrollY)
        assertTrue(port.landingHoldCleared)
    }

    // ---- instrumentation (ReaderDecoration channel) --------------------------
    // Pins the log emissions the in-app Debug logs screen relies on to correlate
    // "highlight silently failed to render" reports with the underlying apply/complete
    // events. A revert of the instrumentation flips these red.

    @Test
    fun `applyAnnotationHighlights logs entry and per-webview branch to ReaderDecoration channel`() {
        val logger = RecordingLogger()
        val port = FakePort()
        val c = ContinuousDecorationController(port).apply { this.logger = logger }
        val hrefWithMark = "ch-1.xhtml"
        val hrefWithoutMark = "ch-2.xhtml"
        val wvWith = FakeChapterWebView(chapterHref = hrefWithMark).also { port.loaded += it }
        val wvWithout = FakeChapterWebView(chapterHref = hrefWithoutMark).also { port.loaded += it }

        c.applyAnnotationHighlights(
            mapOf(hrefWithMark to listOf(AnnotationHighlight("id1", "text", "rgba(0,0,0,1)"))),
        )
        wvWith.completeLast()

        val msgs = logger.records(LogChannel.ReaderDecoration).map { it.message }
        assertTrue(
            "entry log missing: $msgs",
            msgs.any { it.startsWith("applyAnnotationHighlights") && it.contains(hrefWithMark) },
        )
        assertTrue(
            "apply→highlights branch log missing: $msgs",
            msgs.any { it.startsWith("apply→highlights") && it.contains("href='$hrefWithMark'") },
        )
        assertTrue(
            "apply→clear branch log missing: $msgs",
            msgs.any { it.startsWith("apply→clear") && it.contains("href='$hrefWithoutMark'") },
        )
        assertTrue(
            "apply-complete log missing: $msgs",
            msgs.any { it.startsWith("apply-complete") && it.contains("href='$hrefWithMark'") },
        )
    }

    @Test
    fun `onChapterLoaded logs annotation count for the loaded href`() {
        val logger = RecordingLogger()
        val port = FakePort()
        val c = ContinuousDecorationController(port).apply { this.logger = logger }
        val href = "ch-1.xhtml"
        c.applyAnnotationHighlights(
            mapOf(href to listOf(AnnotationHighlight("id1", "text", "rgba(0,0,0,1)"))),
        )
        logger.clear()
        val wv = FakeChapterWebView(chapterHref = href).also { port.loaded += it }

        c.onChapterLoaded(wv)

        val msgs = logger.records(LogChannel.ReaderDecoration).map { it.message }
        assertTrue(
            "onChapterLoaded log missing count=1: $msgs",
            msgs.any {
                it.startsWith("onChapterLoaded") && it.contains("href='$href'") && it.contains("annotationsForHref=1")
            },
        )
    }

    // Cold-open regression: chapters that never received annotation marks (because the annotation
    // Flow hasn't emitted yet) must NOT get a redundant `CLEAR_ANNOTATION_HIGHLIGHTS_JS` eval —
    // there's nothing in the DOM to scrub, and per-chapter JS evals show up as ~60-100ms Main-thread
    // stalls on emulator cold-open. Flips red if the "never-applied" guard is removed.
    @Test
    fun `applyAnnotationHighlights skips clear JS on hrefs that never received marks`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val wv = FakeChapterWebView(chapterHref = "ch-a.xhtml").also { port.loaded += it }

        // Annotation Flow hasn't emitted yet, but chapter is already loaded and reader's
        // LaunchedEffect fires applyAnnotationHighlights with an empty map.
        c.applyAnnotationHighlights(emptyMap())

        assertEquals(
            "empty apply on a never-applied href must not dispatch any JS",
            0,
            wv.evaluatedJsCount,
        )
    }

    // Cold-open regression: the reader's LaunchedEffect re-fires applyAnnotationHighlights on every
    // pageLoadGeneration / reflowGeneration bump. On cold-open with 3 initial-window chapters, that's
    // 3+ back-to-back calls with the identical mark list. The dedup guard skips the JS eval when the
    // per-href mark list is content-equal to the last one applied. Flips red if the guard is removed.
    @Test
    fun `applyAnnotationHighlights skips redundant apply when marks are content-equal to last`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val href = "ch-a.xhtml"
        val wv = FakeChapterWebView(chapterHref = href).also { port.loaded += it }
        val marks = listOf(
            AnnotationHighlight("id1", "text-1", "rgba(255,0,0,0.4)"),
            AnnotationHighlight("id2", "text-2", "rgba(0,255,0,0.4)"),
        )

        c.applyAnnotationHighlights(mapOf(href to marks))
        val afterFirst = wv.evaluatedJsCount
        assertEquals("first apply dispatches once", 1, afterFirst)

        // Content-equal re-apply (same list, same order) — the dedup guard must short-circuit.
        c.applyAnnotationHighlights(mapOf(href to marks.toList()))
        assertEquals(
            "content-equal re-apply must skip the JS eval",
            afterFirst,
            wv.evaluatedJsCount,
        )
        c.applyAnnotationHighlights(mapOf(href to marks.toList()))
        assertEquals(
            "further content-equal re-applies must also skip",
            afterFirst,
            wv.evaluatedJsCount,
        )
    }

    // Behaviour-preservation: the dedup guard MUST NOT skip when the mark list actually changes
    // (add / remove / edit). Flips red if someone tightens the guard to (href-only) and loses
    // real re-application when the user edits an annotation on the current chapter.
    @Test
    fun `applyAnnotationHighlights re-applies when marks change`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val href = "ch-a.xhtml"
        val wv = FakeChapterWebView(chapterHref = href).also { port.loaded += it }
        val initial = listOf(AnnotationHighlight("id1", "text-1", "rgba(255,0,0,0.4)"))
        val added = initial + AnnotationHighlight("id2", "text-2", "rgba(0,255,0,0.4)")

        c.applyAnnotationHighlights(mapOf(href to initial))
        c.applyAnnotationHighlights(mapOf(href to added))

        assertEquals(
            "adding a mark must trigger a fresh apply JS eval",
            2,
            wv.evaluatedJsCount,
        )
    }

    // Cross-path regression: `onChapterLoaded` and the LaunchedEffect-driven
    // `applyAnnotationHighlights` are both entry points for pushing marks into a chapter WebView.
    // If onChapterLoaded doesn't seed the dedup memo, the subsequent LaunchedEffect call with the
    // same marks would re-run the full JS eval — the exact pattern seen in the pre-fix
    // cold-open trace. Pins the memo-seeding behaviour.
    @Test
    fun `onChapterLoaded seeds dedup so subsequent identical apply short-circuits`() {
        val port = FakePort()
        val c = ContinuousDecorationController(port)
        val href = "ch-a.xhtml"
        val marks = listOf(AnnotationHighlight("id1", "text-1", "rgba(255,0,0,0.4)"))

        // Pre-load marks into the controller state, then simulate the WebView's onPageFinished
        // triggering onChapterLoaded — the controller must apply the marks AND remember it did.
        c.applyAnnotationHighlights(mapOf(href to marks))
        val wv = FakeChapterWebView(chapterHref = href).also { port.loaded += it }
        c.onChapterLoaded(wv)
        val afterLoad = wv.evaluatedJsCount
        assertTrue("onChapterLoaded should dispatch the apply JS", afterLoad >= 1)

        // Now the LaunchedEffect fires applyAnnotationHighlights with the same map — the dedup
        // guard must skip the JS eval on this chapter.
        c.applyAnnotationHighlights(mapOf(href to marks))
        assertEquals(
            "identical LaunchedEffect-driven apply after onChapterLoaded must be a no-op",
            afterLoad,
            wv.evaluatedJsCount,
        )
    }
}
