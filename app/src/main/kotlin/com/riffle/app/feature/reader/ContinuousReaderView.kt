package com.riffle.app.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.OverScroller
import androidx.core.widget.NestedScrollView
import com.riffle.core.domain.FormattingPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import kotlin.math.abs

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

    companion object {
        /** Chapters kept loaded behind the reader (for smooth backward scrolling). */
        private const val CHAPTERS_BEHIND = 1

        /**
         * Chapters kept loaded ahead of the reader. Must be ≥2 so that a short "CHAPTER N"
         * divider page and the real content chapter that follows it are BOTH loaded and measured
         * before the reader arrives — otherwise the content chapter starts loading exactly when
         * the reader scrolls into it, producing a blank gap, a spinner, and a jump at the seam.
         */
        private const val CHAPTERS_AHEAD = 3

        /** Total sliding-window size: the reader's chapter plus the behind/ahead buffers. */
        private const val WINDOW_SIZE = CHAPTERS_BEHIND + 1 + CHAPTERS_AHEAD
    }

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
     * The window covers [topIndex .. topIndex + loadedCount - 1] (clamped to list bounds),
     * keeping [CHAPTERS_BEHIND] chapters behind the reader and [CHAPTERS_AHEAD] ahead.
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

    /**
     * True while a window-shift is scheduled (posted) but not yet executed. Shifts are deferred off
     * the scroll callback (see [handleScrollChange]); this coalesces the many scroll events during a
     * fling into a single pending shift so we don't queue a backlog of redundant shift runnables.
     */
    private var shiftPending = false

    /** Measured content heights for each WebView in the current window. */
    private val measuredHeights = mutableListOf<Int>()

    /**
     * Window indices (0-based) of chapters that must report their real height before the
     * initial scroll fires. Populated in [initialize]; cleared as each chapter measures.
     * When the set empties, [pendingInitialScroll] is invoked and nulled out.
     */
    private val pendingInitialMeasureIndices = mutableSetOf<Int>()

    /**
     * Closure that performs the initial [scrollTo] once all chapters in
     * [pendingInitialMeasureIndices] have reported their real heights.
     * Null after the initial scroll has fired or when opening at position 0.
     */
    private var pendingInitialScroll: (() -> Unit)? = null

    /**
     * Placeholder height used before real measurement arrives. One screen height keeps the
     * forward-shift trigger working (the last chapter needs enough height to scroll into) while
     * minimising the phantom space the user can fling into before the real height is known.
     * Three screen heights caused a large snap-back when short chapters (e.g. chapter-number
     * divider pages) measured far below the placeholder.
     */
    private val placeholderHeight: Int get() = resources.displayMetrics.heightPixels

    /**
     * Pool of detached [ChapterWebView]s kept for reuse across window shifts. Constructing a WebView
     * costs ~5-15ms on the main thread — doing it inside the scroll callback at every chapter border
     * drops a frame and reads as a stutter. Recycling the WebView that just scrolled off the far end
     * into the one being added at the near end keeps shifts cheap (a reload, no construction/destroy).
     * Capped at [WINDOW_SIZE]; any excess is destroyed.
     */
    private val recycledViews = ArrayDeque<ChapterWebView>()

    /** A recycled WebView if one is available, else a freshly constructed one. */
    private fun obtainWebView(): ChapterWebView =
        recycledViews.removeFirstOrNull() ?: ChapterWebView(context)

    /**
     * Detach [wv] from active use and pool it for reuse (or destroy it if the pool is full).
     * Callbacks are cleared so an in-flight measure/page-finished from its previous chapter can't
     * fire against stale state before the WebView is reloaded for its next chapter.
     */
    private fun recycle(wv: ChapterWebView) {
        wv.onHeightMeasured = null
        wv.onPageFinished = null
        wv.onTap = null
        if (recycledViews.size < WINDOW_SIZE) recycledViews.addLast(wv) else wv.destroy()
    }

    init {
        addView(container, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            handleScrollChange(scrollY)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Destroy every WebView we hold — both the live window and the recycle pool — so they don't
        // leak when the reader screen goes away.
        webViews.forEach { it.destroy() }
        webViews.clear()
        recycledViews.forEach { it.destroy() }
        recycledViews.clear()
    }

    // ChapterWebViews detect vertical motion and call requestDisallowInterceptTouchEvent(true),
    // which would prevent NestedScrollView from intercepting and owning the scroll — exactly the
    // same bug ScrollBoundaryNavigationContainer solves for Readium WebViews. We are the scroll
    // owner, so we never yield the right to intercept.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) return
        super.requestDisallowInterceptTouchEvent(false)
    }

    // Intercept vertical movement as soon as it exceeds a minimal threshold (half the system
    // touch slop). NestedScrollView's default is to wait for a full touch slop before
    // intercepting, which leaves the WebView handling touch for long enough that the user
    // perceives scroll resistance ("fighting"). Intercepting earlier gives us scroll ownership
    // from the very first detectable movement, matching native smooth-scroll feel.
    private var interceptDownY = 0f
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> interceptDownY = ev.y
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.y - interceptDownY) > touchSlop / 2f) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

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

        // Open with the TARGET chapter at the top of the window (no behind buffer yet). The behind
        // buffer exists only for smooth *backward* scrolling, which the reader can't do until after
        // the first frame — so blocking the first paint on it just makes opening slow. With the
        // target at window-index 0 its layout top is 0, so the initial scroll offset depends only on
        // the target's own height: we wait for exactly one chapter to measure, not two.
        topIndex = centerIndex
        val initialAhead = minOf(1 + CHAPTERS_AHEAD, chapters.size - topIndex)

        pendingInitialMeasureIndices.clear()
        pendingInitialMeasureIndices.add(0) // the target chapter only
        pendingInitialScroll = {
            val window = buildWindow()
            val offset = ContinuousPositionTracker.scrollOffsetFor(initialHref, initialProgression, window)
            if (offset != null) scrollTo(0, (offset - height / 2).coerceAtLeast(0))
            // Now that the target is visible, fill in the behind buffer for smooth backward
            // scrolling. prependChapter compensates scroll for the added height, so the content the
            // user is already looking at stays visually anchored — no post-paint jump.
            // shiftInProgress guards the compensating scrollBy from re-entering handleScrollChange
            // and oscillating (the same guard the on-scroll shifts use).
            post {
                shiftInProgress = true
                repeat(CHAPTERS_BEHIND) {
                    if (topIndex > 0) {
                        topIndex--
                        prependChapter(topIndex)
                    }
                }
                shiftInProgress = false
            }
        }

        repeat(initialAhead) { i -> appendChapter(topIndex + i) }
    }

    /** Update preferences and re-inject styles + remeasure all loaded chapters. */
    fun updatePreferences(prefs: FormattingPreferences) {
        if (prefs == formattingPrefs) return
        formattingPrefs = prefs
        val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(prefs)
        webViews.forEach { wv -> wv.reinjectAndRemeasure(styleJs) }
    }

    /** Scroll to [href] at [progression]. Loads the chapter into the window if needed. */
    fun navigateTo(href: String, progression: Float) {
        val targetIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        if (targetIndex < 0) return
        if (targetIndex < topIndex || targetIndex > topIndex + webViews.size - 1) {
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
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        wv.onTap = { onTap?.invoke() }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val wasPlaceholder = measuredHeights[i] == placeholder
                val oldHeight = measuredHeights[i]
                val delta = measuredPx - oldHeight
                // For non-first chapters whose placeholder shrank to the real height: abort the
                // fling and clamp scrollY to the new content boundary BEFORE updating layoutParams.
                //
                // Without aborting the fling: NestedScrollView.computeScroll() continues running
                // the OverScroller between our scrollTo() call and the layout pass, overwriting
                // the clamped position before onLayout's scrollTo() re-clamps with the new height
                // — the user sees the same "snap back" because the fling won the race.
                //
                // Without the pre-layout scrollTo: the implicit clamp in NestedScrollView.onLayout
                // fires invisibly with no smooth-scroll, producing a jarring jump.
                //
                // Short EPUB chapters (separator pages, chapter-number dividers) hit this path
                // regularly — their real height (e.g. 100 px) is far less than the placeholder.
                if (wasPlaceholder && i != 0 && delta < 0) {
                    measuredHeights[i] = measuredPx
                    val newMaxScroll = (measuredHeights.sum() - height).coerceAtLeast(0)
                    if (scrollY > newMaxScroll) {
                        abortFling()
                        scrollTo(0, newMaxScroll)
                    }
                } else {
                    measuredHeights[i] = measuredPx
                }
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                // Compensate scroll for ch0 only when:
                // - chapter shrank (delta < 0): visible content above would shift without compensation
                // - scrollY is past old ch0 boundary (delta > 0 but user has scrolled past ch0):
                //   ch0 growing pushes ch1+ down; compensate to keep ch1 in place.
                // Never compensate for a growing ch0 we're still scrolled within — that would
                // fire a spurious forward jump and immediately re-trigger a backward shift.
                // Applies to BOTH the first real measurement (wasPlaceholder) and any later
                // re-measure: a chapter can grow after first paint (late font swap, image decode,
                // type-scale settle), and uncompensated growth of an above-viewport ch0 would jump
                // the line being read.
                // Suppressed while initial scroll is pending: the deferred scrollTo below
                // computes the correct position from real heights and fires once; a premature
                // scrollBy here would shift the viewport before that calculation runs.
                if (pendingInitialScroll == null && i == 0 && delta != 0 && (delta < 0 || scrollY >= oldHeight)) {
                    scrollBy(0, delta)
                }

                // Fire the initial scroll once every chapter at or before the target chapter
                // has reported its real height so the scroll position is accurate.
                if (wasPlaceholder && pendingInitialMeasureIndices.remove(i) &&
                    pendingInitialMeasureIndices.isEmpty()
                ) {
                    val scroll = pendingInitialScroll
                    pendingInitialScroll = null
                    scroll?.invoke()
                }
            }
        }
        wv.onPageFinished = {
            val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(styleJs)
        }
        webViews.add(wv)
        measuredHeights.add(placeholder)
        container.addView(wv, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        wv.loadChapter(entry.link.href.toString(), entry.url, formattingPrefs)
    }

    private fun prependChapter(index: Int) {
        val entry = allChapters[index]
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        wv.onTap = { onTap?.invoke() }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val delta = measuredPx - measuredHeights[i]
                measuredHeights[i] = measuredPx
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                // A prepended chapter is above the viewport, so any height change (the first real
                // measurement replacing the placeholder, or a later reflow re-measure) must be
                // compensated to keep the content the user is reading visually anchored.
                if (delta != 0) scrollBy(0, delta)
            }
        }
        wv.onPageFinished = {
            val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(styleJs)
        }
        webViews.add(0, wv)
        measuredHeights.add(0, placeholder)
        container.addView(wv, 0, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        scrollBy(0, placeholder)
        wv.loadChapter(entry.link.href.toString(), entry.url, formattingPrefs)
    }

    private fun removeTop() {
        if (webViews.isEmpty()) return
        val h = measuredHeights.removeAt(0)
        val wv = webViews.removeAt(0)
        container.removeView(wv)
        recycle(wv)
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
        recycle(wv)
    }

    private fun handleScrollChange(scrollY: Int) {
        if (shiftInProgress) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val (href, progression) = ContinuousPositionTracker.locatorAt(scrollY, height, window)
        onPositionChanged?.invoke(href, progression)

        // Defer window shifts off the scroll callback. A shift's compensating scrollBy() would
        // otherwise run re-entrantly inside NestedScrollView.computeScroll() (this callback fires
        // synchronously from there during a fling); computeScroll then mis-reads the extra scroll
        // delta as having hit a content edge and aborts the OverScroller — killing the fling's
        // momentum at every chapter boundary. Running the shift in a posted runnable (next message,
        // outside computeScroll) lets the fling continue smoothly across the seam. shiftPending
        // coalesces the per-frame scroll events into a single scheduled shift.
        if (!shiftPending) {
            shiftPending = true
            post {
                shiftPending = false
                maybeShift()
            }
        }
    }

    /**
     * Evaluate the current scroll position and shift the sliding window by one chapter if needed.
     * Runs from a posted runnable (never re-entrantly inside the fling computation) so the
     * compensating scrollBy() does not abort an in-progress fling.
     */
    private fun maybeShift() {
        if (shiftInProgress) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val sY = scrollY
        val (href, _) = ContinuousPositionTracker.locatorAt(sY, height, window)

        // BACKWARD: fire when scrollY is in the first half of the first chapter. This gives a
        // hysteresis gap that prevents the oscillation that a chapter-index check causes —
        // after every FORWARD shift, the scrollBy adjustment lands the viewport inside the new
        // first chapter (midIdx == topIdx), which would immediately re-trigger BACKWARD.
        // A scrollY threshold is immune to that because the post-shift scrollY is deep inside
        // the first chapter (far past the midpoint), not near the top.
        //
        // FORWARD: look-ahead based — fire when the viewport-midpoint chapter has advanced more
        // than CHAPTERS_BEHIND slots past the window top, so several chapters stay loaded ahead
        // of the reader (see ContinuousPositionTracker.forwardShiftNeeded).
        val firstChapterHeight = window.firstOrNull()?.height ?: 0
        val viewportMidIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        val shouldShiftBackward = sY < firstChapterHeight / 2 && topIndex > 0
        val shouldShiftForward = ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = viewportMidIndex,
            topIndex = topIndex,
            loadedChapterCount = webViews.size,
            readingOrderSize = allChapters.size,
            chaptersBehind = CHAPTERS_BEHIND,
        )
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
                removeTop() // topIndex already incremented inside removeTop()
                // After removeTop the window covers [topIndex .. topIndex + size - 1]; the next
                // chapter to append is the one immediately past the current last slot.
                val nextIndex = topIndex + webViews.size
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
        topIndex = (centerIndex - CHAPTERS_BEHIND).coerceAtLeast(0)
        val windowSize = minOf(WINDOW_SIZE, allChapters.size - topIndex)
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

    /**
     * Abort any in-progress fling animation so that the OverScroller stops updating scrollY.
     * Called before a programmatic scrollTo when a chapter measures shorter than its placeholder:
     * without this, computeScroll() would continue advancing the fling position between our
     * scrollTo() call and the layout pass, overwriting the clamped scroll before onLayout's own
     * clamp fires with the updated (smaller) content height.
     *
     * NestedScrollView does not expose a public abort-fling API, so we reach the mScroller field
     * via reflection. The field name is stable across all AOSP/AndroidX versions.
     */
    private fun abortFling() {
        try {
            val f = NestedScrollView::class.java.getDeclaredField("mScroller")
            f.isAccessible = true
            (f.get(this) as? OverScroller)?.abortAnimation()
        } catch (_: Exception) {}
    }
}
