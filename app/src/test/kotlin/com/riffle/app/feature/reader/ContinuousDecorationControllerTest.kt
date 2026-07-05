package com.riffle.app.feature.reader

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

        c.highlightInChapter("ch-1.xhtml", "some text", "rgba(0,0,0,1)")
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

        c.highlightInChapter("ch-1.xhtml", "some text", "rgba(0,0,0,1)")
        wv.completeLast()
        wv.completeLast("400")

        assertEquals(67, port.lastSmoothScrollY)
        assertTrue(port.landingHoldCleared)
    }
}
