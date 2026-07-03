package com.riffle.app.feature.reader

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

    private var currentAnnotationsByHref: Map<String, List<AnnotationHighlight>> = emptyMap()
    private var currentSearchHighlights: SearchHighlightsState? = null

    /** Called by [ContinuousReaderView.onPageFinished] once a chapter's page has loaded so it
     *  re-applies whatever decorations belong to it. */
    fun onChapterLoaded(wv: ChapterWebViewLike, onAnnotationsApplied: () -> Unit = {}) {
        val annotations = currentAnnotationsByHref[wv.chapterHref]
        if (!annotations.isNullOrEmpty()) {
            wv.evaluateJavascript(ContinuousStyleInjector.applyAnnotationHighlightsJs(annotations)) { _ ->
                onAnnotationsApplied()
            }
        }
        val search = currentSearchHighlights
        if (search != null && search.resultsByHref.containsKey(wv.chapterHref)) {
            applySearchTo(wv, search)
        }
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
        port.forEachLoadedWebView { wv ->
            val href = wv.chapterHref
            if (href.isEmpty()) return@forEachLoadedWebView
            val annotations = annotationsByHref[href]
            if (annotations.isNullOrEmpty()) {
                wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_ANNOTATION_HIGHLIGHTS_JS, null)
            } else {
                wv.evaluateJavascript(ContinuousStyleInjector.applyAnnotationHighlightsJs(annotations)) { _ ->
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
        val direct = port.findLoadedWebView(href)
        if (direct != null) {
            direct.evaluateJavascript(ContinuousStyleInjector.highlightTextJs(text, cssColor)) { _ ->
                // Re-lookup by href rather than reusing wv directly: the window may have shifted
                // between the evaluateJavascript call and this callback.
                scrollToReadaloudHighlight(href)
            }
            return
        }
        // Fallback for matched ABS books: the readaloud [fragmentRef] carries the Storyteller BUNDLE
        // href (e.g. `text/part0006_split_XXX.html`), but this window's loaded WebViews carry ABS
        // hrefs (e.g. `xhtml/foo.html`) — the direct match above misses. In paginated mode Readium's
        // DecorableNavigator anchors highlights by TEXT (TextQuoteAnchor) so this href mismatch is
        // invisible there, but continuous mode looks up by exact href. Fall back to applying the
        // text-based highlight to EVERY loaded WebView; the mark JS is a no-op on WebViews whose
        // document doesn't contain [text], so at most one actually paints. Clearing on chapter
        // change still works because a subsequent apply on a different fragmentRef triggers
        // [clearHighlightInChapter] via the renderer's prevSentenceHref, and any stale mark on a
        // non-matching WebView is cleared by the sentinel-and-replace step inside
        // [ContinuousStyleInjector.highlightTextJs] the next time it runs on that WebView.
        port.forEachLoadedWebView { wv ->
            wv.evaluateJavascript(ContinuousStyleInjector.highlightTextJs(text, cssColor), null)
        }
        // Scroll uses [href] as the slot key; without a translated key it's not safe to auto-scroll,
        // so the fallback only paints the highlight. Auto-scroll follow for matched-book continuous
        // mode is a separate follow-up (the paginated auto-follow via ColumnSnap is orthogonal).
    }

    override fun clearHighlightInChapter(href: String) {
        val wv = port.findLoadedWebView(href)
        if (wv != null) {
            wv.evaluateJavascript(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, null)
            return
        }
        // Symmetric with the highlightInChapter fallback: on matched ABS books the ref carries the
        // bundle href which doesn't match any loaded WebView, so the highlight was broadcast; clear
        // it from every loaded WebView too. The CLEAR JS is a no-op on WebViews that don't have
        // the `_riffle_hl` mark, so this is safe to fan out.
        port.forEachLoadedWebView { it.evaluateJavascript(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, null) }
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
