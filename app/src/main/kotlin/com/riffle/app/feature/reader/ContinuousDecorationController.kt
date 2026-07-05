package com.riffle.app.feature.reader

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.NoopLogger
import kotlin.math.abs

/**
 * Owns annotation + search decoration state for continuous mode and applies it to the current
 * sliding-window of loaded chapters.
 *
 * Moved out of [ContinuousReaderView] so:
 *  - state persistence (currentAnnotationsByHref, currentSearchHighlights) is JVM-testable,
 *  - the View no longer holds every decoration path alongside the sliding-window state machine,
 *  - a chapter entering the window (onPageFinished) has a single re-apply entry point:
 *    [onChapterLoaded].
 *
 * Sentence highlight orchestration (Readaloud today, Cadence in future) remains in the
 * pre-existing [ContinuousHighlightRenderer] which owns `prevSentenceHref`; the controller
 * only exposes [highlightInChapter] /
 * [clearHighlightInChapter] because they are part of the [ContinuousHighlightTarget] contract.
 */
internal class ContinuousDecorationController(
    private val port: Port,
) : ContinuousHighlightTarget {

    interface Port {
        fun forEachLoadedWebView(block: (ChapterWebViewLike) -> Unit)
        fun findLoadedWebView(href: String): ChapterWebViewLike?
        fun scrollTo(y: Int)
        fun smoothScrollTo(y: Int)
        fun clearLandingHold()
        fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot>
        val viewportHeightPx: Int
        val currentScrollY: Int
    }

    /** Set by the host ([ContinuousWindowController.logger]) once the reader is wired up.
     *  Emissions land on [LogChannel.ReaderDecoration] (tag `RIFFLE_DECO`) and mirror to
     *  the in-app debug log screen — see docs/agents/domain.md. */
    internal var logger: Logger = NoopLogger

    private var currentAnnotationsByHref: Map<String, List<AnnotationHighlight>> = emptyMap()
    private var currentSearchHighlights: SearchHighlightsState? = null

    /**
     * Cadence chapter-load hook (issue #403). The reader screen sets this on session bind so
     * every chapter entering the sliding window triggers a fresh DOM tokenisation for Cadence.
     * Null-safe — Cadence is opt-in per book + WebView-gated.
     */
    private var cadenceOnChapterLoaded: ((wv: ChapterWebViewLike) -> Unit)? = null

    /** Called by [EpubReaderScreen] once the Cadence controller is bound to the current book. */
    fun setCadenceOnChapterLoaded(hook: ((wv: ChapterWebViewLike) -> Unit)?) {
        cadenceOnChapterLoaded = hook
    }

    /** Called by [ContinuousReaderView.onPageFinished] once a chapter's page has loaded so it
     *  re-applies whatever decorations belong to it. */
    fun onChapterLoaded(wv: ChapterWebViewLike, onAnnotationsApplied: () -> Unit = {}) {
        val href = wv.chapterHref
        val annotations = currentAnnotationsByHref[href]
        val count = annotations?.size ?: 0
        logger.d(LogChannel.ReaderDecoration) {
            "onChapterLoaded href='$href' annotationsForHref=$count knownHrefs=${currentAnnotationsByHref.size}"
        }
        if (!annotations.isNullOrEmpty()) {
            wv.evaluateJavascript(ContinuousStyleInjector.applyAnnotationHighlightsJs(annotations)) { _ ->
                logger.d(LogChannel.ReaderDecoration) {
                    "onChapterLoaded apply-complete href='$href' count=$count"
                }
                onAnnotationsApplied()
            }
        }
        val search = currentSearchHighlights
        if (search != null && search.resultsByHref.containsKey(href)) {
            applySearchTo(wv, search)
        }
        // Cadence tokenises the chapter DOM once per chapter enter — the reader screen's hook
        // runs CadenceDomScript.tokeniseChapterJs and hands the parsed maps back to the VM.
        cadenceOnChapterLoaded?.invoke(wv)
    }

    override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) {
        applyAnnotationHighlights(annotationsByHref, onEachApplied = {})
    }

    /**
     * Bulk-apply persisted annotation highlights to all currently loaded chapters and remember the
     * state so that chapters entering the sliding window later (via [onChapterLoaded]) automatically
     * receive their marks.
     *
     * [onEachApplied] fires once per WebView that received annotation-apply JS (not clear JS), once
     * that JS completes — mirrors the completion callback the View wires to re-fire its pending
     * landing/focus logic ([ContinuousReaderView.onAnnotationHighlightsApplied]).
     */
    fun applyAnnotationHighlights(
        annotationsByHref: Map<String, List<AnnotationHighlight>>,
        onEachApplied: (ChapterWebViewLike) -> Unit,
    ) {
        currentAnnotationsByHref = annotationsByHref
        val loadedHrefs = mutableListOf<String>()
        port.forEachLoadedWebView { loadedHrefs += it.chapterHref }
        logger.d(LogChannel.ReaderDecoration) {
            val perHref = annotationsByHref.entries.joinToString(",") { "${it.key}=${it.value.size}" }
            "applyAnnotationHighlights hrefsWithMarks=${annotationsByHref.size} loadedHrefs=$loadedHrefs perHref=[$perHref]"
        }
        port.forEachLoadedWebView { wv ->
            val href = wv.chapterHref
            if (href.isEmpty()) {
                logger.w(LogChannel.ReaderDecoration) { "applyAnnotationHighlights skip: empty chapterHref on WebView" }
                return@forEachLoadedWebView
            }
            val annotations = annotationsByHref[href]
            if (annotations.isNullOrEmpty()) {
                logger.d(LogChannel.ReaderDecoration) { "apply→clear href='$href'" }
                wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_ANNOTATION_HIGHLIGHTS_JS, null)
            } else {
                logger.d(LogChannel.ReaderDecoration) { "apply→highlights href='$href' count=${annotations.size}" }
                wv.evaluateJavascript(ContinuousStyleInjector.applyAnnotationHighlightsJs(annotations)) { _ ->
                    logger.d(LogChannel.ReaderDecoration) { "apply-complete href='$href' count=${annotations.size}" }
                    onEachApplied(wv)
                }
            }
        }
    }

    override fun applySearchHighlights(state: SearchHighlightsState?) {
        currentSearchHighlights = state
        if (state == null) {
            port.forEachLoadedWebView { wv ->
                wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_SEARCH_HIGHLIGHTS_JS, null)
            }
            return
        }
        port.forEachLoadedWebView { applySearchTo(it, state) }
    }

    private fun applySearchTo(wv: ChapterWebViewLike, state: SearchHighlightsState) {
        val href = wv.chapterHref
        if (href.isEmpty()) return
        val texts = state.resultsByHref[href] ?: return
        val isActive = href == state.activeHref
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = texts,
            inactiveCssColor = state.inactiveCssColor,
            activeText = if (isActive) state.activeText else null,
            activeProgression = if (isActive) state.activeProgression else -1f,
            activeCssColor = state.activeCssColor,
        )
        // Clear first, then re-apply in callback: Chrome won't reliably find text in a DOM
        // that was just mutated synchronously (see ContinuousStyleInjector.applyAnnotationHighlightsJs).
        wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_SEARCH_HIGHLIGHTS_JS) { _ ->
            wv.evaluateJavascript(js, null)
        }
    }

    override fun highlightInChapter(href: String, text: String, cssColor: String) {
        val wv = port.findLoadedWebView(href) ?: return
        wv.evaluateJavascript(ContinuousStyleInjector.highlightTextJs(text, cssColor)) { _ ->
            // Re-lookup by href rather than reusing wv directly: the window may have shifted
            // between the evaluateJavascript call and this callback.
            scrollToReadaloudHighlight(href)
        }
    }

    override fun clearHighlightInChapter(href: String) {
        val wv = port.findLoadedWebView(href) ?: return
        wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, null)
    }

    /** Same recipe as the prior in-View `scrollToHighlight`: query `_riffle_hl`'s device-px Y,
     *  add slot.top, subtract height/3, then scroll iff |delta| > height/8. */
    private fun scrollToReadaloudHighlight(href: String) {
        val wv = port.findLoadedWebView(href) ?: return
        wv.evaluateJavascript(
            """(function(){
                var el=document.getElementById('_riffle_hl');
                if(!el) return -1;
                var r=el.getBoundingClientRect();
                var y=r.top+(window.pageYOffset||document.documentElement.scrollTop||0);
                return Math.max(0,Math.round(y*(window.devicePixelRatio||1)));
            })()""",
        ) { result ->
            val elementTop = result?.toFloatOrNull()?.toInt() ?: return@evaluateJavascript
            if (elementTop < 0) return@evaluateJavascript
            // Re-lookup window state fresh — a shift between the two evaluateJavascript calls
            // would have changed slot positions.
            val slot = port.buildWindow().firstOrNull { it.href == href } ?: return@evaluateJavascript
            val vh = port.viewportHeightPx
            val absoluteY = slot.top + elementTop
            val targetScrollY = (absoluteY - vh / 3).coerceAtLeast(0)
            // Scroll whenever the current position is more than height/8 away from the target.
            // Using abs() avoids a bug where a target just inside the top of the viewport would
            // trigger a spurious backward scroll (target < scrollY + margin => target < scrollY).
            if (abs(targetScrollY - port.currentScrollY) > vh / 8) {
                port.clearLandingHold()
                port.smoothScrollTo(targetScrollY)
            }
        }
    }
}
