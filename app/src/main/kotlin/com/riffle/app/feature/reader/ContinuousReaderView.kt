package com.riffle.app.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.riffle.core.domain.FormattingPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/**
 * Renders the entire book as a single vertical scroll by stacking a sliding window of 3
 * [ChapterWebView]s (previous, current, next) inside a [LinearLayout].
 *
 * Scrolling is owned entirely by this [NestedScrollView]; each [ChapterWebView] has its
 * internal scrolling disabled and its height fixed to its measured content height.
 *
 * Window shifting: when [currentChapterIndex] advances past the bottom chapter or retreats
 * past the top chapter, the far end is destroyed and a new chapter is added at the other end.
 * Adding a chapter at the TOP adjusts [scrollY] by the new chapter's height to keep the
 * visible content stable.
 */
internal class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    data class ChapterEntry(val link: Link, val url: String)

    /** Called on main thread when position changes; supplies `href` and `progression`. */
    var onPositionChanged: ((href: String, progression: Float) -> Unit)? = null

    /**
     * Called on main thread when the user taps a chapter without scrolling.
     * Wire to the reader's chrome toggle so top/bottom bars show/hide on tap.
     */
    var onTap: (() -> Unit)? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** All chapters in reading order. Set once via [initialize]. */
    private var allChapters: List<ChapterEntry> = emptyList()

    /** Current formatting preferences for CSS injection. */
    private var formattingPrefs: FormattingPreferences = FormattingPreferences()

    /** Publication used by [ChapterWebView] to serve EPUB resources via shouldInterceptRequest. */
    private var publication: Publication? = null

    /**
     * Index into [allChapters] of the topmost loaded chapter.
     * The window covers [topIndex, topIndex+1, topIndex+2] (clamped to list bounds).
     */
    private var topIndex: Int = 0

    /** Parallel list to the loaded WebViews; index i matches container.getChildAt(i). */
    private val webViews = mutableListOf<ChapterWebView>()

    /**
     * True while a window-shift operation (removeTop/removeBottom/prependChapter) is in
     * progress. Prevents re-entrant handleScrollChange calls — programmatic scrollBy() calls
     * inside removeTop() and prependChapter() would otherwise fire a second shift mid-operation.
     */
    private var shiftInProgress = false

    /** Measured content heights for each WebView in the current window. */
    private val measuredHeights = mutableListOf<Int>()

    /** Placeholder height (3× screen height) used before real measurement arrives. */
    private val placeholderHeight: Int get() = resources.displayMetrics.heightPixels * 3

    init {
        addView(container, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            handleScrollChange(scrollY)
        }
    }

    // ChapterWebViews detect vertical motion and call requestDisallowInterceptTouchEvent(true),
    // which would prevent NestedScrollView from intercepting and owning the scroll — exactly the
    // same bug ScrollBoundaryNavigationContainer solves for Readium WebViews. We are the scroll
    // owner, so we never yield the right to intercept.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) return
        super.requestDisallowInterceptTouchEvent(false)
    }

    /**
     * Initialize the view at [initialHref] + [initialProgression].
     * Call once after attaching to the window.
     */
    fun initialize(
        chapters: List<ChapterEntry>,
        prefs: FormattingPreferences,
        initialHref: String,
        initialProgression: Float,
        publication: Publication,
    ) {
        allChapters = chapters
        formattingPrefs = prefs
        this.publication = publication
        val centerIndex = chapters.indexOfFirst { it.link.href.toString() == initialHref }
            .coerceAtLeast(0)
        topIndex = (centerIndex - 1).coerceAtLeast(0)
        val windowSize = minOf(3, chapters.size - topIndex)
        repeat(windowSize) { i -> appendChapter(topIndex + i) }
        post {
            val window = buildWindow()
            val offset = ContinuousPositionTracker.scrollOffsetFor(initialHref, initialProgression, window)
            if (offset != null) scrollTo(0, (offset - height / 2).coerceAtLeast(0))
        }
    }

    /** Update preferences and re-inject styles + remeasure all loaded chapters. */
    fun updatePreferences(prefs: FormattingPreferences) {
        formattingPrefs = prefs
        val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(prefs)
        webViews.forEach { wv -> wv.reinjectAndRemeasure(variableJs) }
    }

    /** Scroll to [href] at [progression]. Loads the chapter into the window if needed. */
    fun navigateTo(href: String, progression: Float) {
        val targetIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        if (targetIndex < 0) return
        if (targetIndex < topIndex || targetIndex > topIndex + 2) {
            rebuildWindowAround(targetIndex)
        }
        // Scroll is computed against current measuredHeights, which may still hold placeholder
        // values if newly-loaded chapters haven't measured yet. The position is approximate until
        // real heights arrive. TODO: add a post-measurement correction callback for navigateTo.
        post {
            val window = buildWindow()
            val offset = ContinuousPositionTracker.scrollOffsetFor(href, progression, window)
            if (offset != null) smoothScrollTo(0, (offset - height / 2).coerceAtLeast(0))
        }
    }

    /** Inject a highlight for [text] in the chapter matching [href]. Clear if blank. */
    fun highlightInChapter(href: String, text: String) {
        val i = webViewIndexFor(href) ?: return
        webViews[i].evaluateJavascript(ContinuousStyleInjector.highlightTextJs(text), null)
    }

    /** Clear any active highlight in the chapter at [href]. */
    fun clearHighlightInChapter(href: String) {
        val i = webViewIndexFor(href) ?: return
        webViews[i].evaluateJavascript(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, null)
    }

    // ── private ────────────────────────────────────────────────────────────────

    private fun appendChapter(index: Int) {
        val entry = allChapters[index]
        val wv = ChapterWebView(context)
        publication?.let { wv.setPublication(it) }
        wv.onTap = { onTap?.invoke() }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val wasPlaceholder = measuredHeights[i] == placeholder
                val oldHeight = measuredHeights[i]
                val delta = measuredPx - oldHeight
                measuredHeights[i] = measuredPx
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                // Compensate scroll for ch0 only when:
                // - chapter shrank (delta < 0): visible content above would shift without compensation
                // - scrollY is past old ch0 boundary (delta > 0 but user has scrolled past ch0):
                //   ch0 growing pushes ch1+ down; compensate to keep ch1 in place.
                // Never compensate for a growing ch0 we're still scrolled within — that would
                // fire a spurious forward jump and immediately re-trigger a backward shift.
                if (wasPlaceholder && i == 0 && (delta < 0 || scrollY >= oldHeight)) {
                    scrollBy(0, delta)
                }
            }
        }
        wv.onPageFinished = {
            val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(variableJs)
        }
        webViews.add(wv)
        measuredHeights.add(placeholder)
        container.addView(wv, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        wv.loadChapter(entry.link.href.toString(), entry.url)
    }

    private fun prependChapter(index: Int) {
        val entry = allChapters[index]
        val wv = ChapterWebView(context)
        publication?.let { wv.setPublication(it) }
        wv.onTap = { onTap?.invoke() }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val wasPlaceholder = measuredHeights[i] == placeholder
                val delta = measuredPx - measuredHeights[i]
                measuredHeights[i] = measuredPx
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                if (wasPlaceholder) scrollBy(0, delta)
            }
        }
        wv.onPageFinished = {
            val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(variableJs)
        }
        webViews.add(0, wv)
        measuredHeights.add(0, placeholder)
        container.addView(wv, 0, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        scrollBy(0, placeholder)
        wv.loadChapter(entry.link.href.toString(), entry.url)
    }

    private fun removeTop() {
        if (webViews.isEmpty()) return
        val h = measuredHeights.removeAt(0)
        val wv = webViews.removeAt(0)
        container.removeView(wv)
        wv.destroy()
        // h is the stored measured height at removal time. If the WebView was still loading when
        // removeTop() fires (h == placeholderHeight), the scroll offset may be over-compensated
        // and produce a brief jump. Rare in practice: removeTop() only fires after the user scrolls
        // past the bottom of the top chapter, by which time it has typically measured.
        scrollBy(0, -h)
        topIndex++
    }

    private fun removeBottom() {
        if (webViews.isEmpty()) return
        measuredHeights.removeAt(measuredHeights.lastIndex)
        val wv = webViews.removeAt(webViews.lastIndex)
        container.removeView(wv)
        wv.destroy()
    }

    private fun handleScrollChange(scrollY: Int) {
        if (shiftInProgress) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val (href, progression) = ContinuousPositionTracker.locatorAt(scrollY, height, window)
        onPositionChanged?.invoke(href, progression)

        // BACKWARD: fire when scrollY is in the first half of the first chapter. This gives a
        // hysteresis gap that prevents the oscillation that a chapter-index check causes —
        // after every FORWARD shift, the scrollBy adjustment lands the viewport inside the new
        // first chapter (midIdx == topIdx), which would immediately re-trigger BACKWARD.
        // A scrollY threshold is immune to that because the post-shift scrollY is deep inside
        // the first chapter (far past the midpoint), not near the top.
        //
        // FORWARD: fire when the viewport bottom enters the last chapter of the window, using
        // the chapter index. Using the bottom edge (not the midpoint) handles short last chapters
        // (shorter than half the viewport) that a midpoint check would never enter.
        val firstChapterHeight = window.firstOrNull()?.height ?: 0
        val totalH = window.sumOf { it.height }
        val (bottomHref, _) = ContinuousPositionTracker.locatorAt(
            (scrollY + height).coerceIn(0, (totalH - 1).coerceAtLeast(0)), 0, window
        )
        val viewportBottomIndex = allChapters.indexOfFirst { it.link.href.toString() == bottomHref }
        val shouldShiftBackward = scrollY < firstChapterHeight / 2 && topIndex > 0
        val shouldShiftForward = ContinuousPositionTracker.forwardShiftNeeded(viewportBottomIndex, topIndex, allChapters.size)
        when {
            shouldShiftBackward -> {
                shiftInProgress = true
                removeBottom()
                topIndex--
                prependChapter(topIndex)
                shiftInProgress = false
            }
            shouldShiftForward -> {
                shiftInProgress = true
                removeTop()
                val nextIndex = topIndex + 2 // topIndex already incremented in removeTop()
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
                shiftInProgress = false
            }
        }
    }

    private fun rebuildWindowAround(centerIndex: Int) {
        webViews.forEach { it.destroy() }
        webViews.clear()
        measuredHeights.clear()
        container.removeAllViews()
        topIndex = (centerIndex - 1).coerceAtLeast(0)
        val windowSize = minOf(3, allChapters.size - topIndex)
        repeat(windowSize) { i -> appendChapter(topIndex + i) }
    }

    private fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot> {
        var top = 0
        return webViews.mapIndexed { i, wv ->
            val h = measuredHeights[i]
            ContinuousPositionTracker.ChapterSlot(wv.chapterHref, top, h).also { top += h }
        }
    }

    private fun webViewIndexFor(href: String): Int? {
        val i = webViews.indexOfFirst { it.chapterHref == href }
        return if (i >= 0) i else null
    }
}
