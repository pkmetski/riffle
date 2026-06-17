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

        /**
         * Grace period after a window (re)build before the initial scroll is forced to fire even if
         * not every required chapter has measured — so a slow/failed measurement can't strand the
         * reader on a blank position. Long enough that a normal load measures first (chapters
         * typically measure in <300ms), short enough that a stuck case recovers quickly.
         */
        private const val INITIAL_SCROLL_FALLBACK_MS = 700L
    }

    data class ChapterEntry(val link: Link, val url: String)

    /** Called on main thread when position changes; supplies `href` and `progression`. */
    var onPositionChanged: ((href: String, progression: Float) -> Unit)? = null

    /**
     * Called on main thread when the user taps a chapter without scrolling.
     * Wire to the reader's chrome toggle so top/bottom bars show/hide on tap.
     */
    var onTap: (() -> Unit)? = null

    /**
     * Called on the main thread when the user taps an in-book link inside a chapter (footnote,
     * cross-reference). [href] is the target resource path (with any `#fragment`). The host wires
     * this to the return-aware navigation path so a "Back" card can restore the pre-jump position.
     */
    var onInternalLinkTapped: ((href: String) -> Unit)? = null

    /**
     * Called on the main thread when the user taps an external (http/https) link. [url] is the
     * absolute URL; the host opens it in a browser.
     */
    var onExternalLinkTapped: ((url: String) -> Unit)? = null

    /** Whether the text-selection menu should offer "Play from here" (readaloud books only). */
    var readaloudAvailable: Boolean = false
        set(value) {
            field = value
            webViews.forEach { it.readaloudAvailable = value }
        }

    /**
     * Called on the main thread with (chapter href, selected text) when the user taps
     * "Play from here". The host resolves the selection to a narrated sentence and starts playback.
     */
    var onPlayFromHereSelection: ((href: String, selectedText: String) -> Unit)? = null

    /**
     * Called on the main thread with the resolved footnote body when the user taps a footnote
     * anchor in a chapter. The host shows the footnote popup.
     */
    var onFootnoteContent: ((FootnoteContent) -> Unit)? = null

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

    /** True while rebuilding the window after a WebView renderer-process death (debounces the
     * per-WebView onRenderProcessGone events that all fire at once). */
    private var rendererRecovering = false

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
        wv.onRenderGone = null
        wv.onInternalLink = null
        wv.onExternalLink = null
        wv.onPlayFromHere = null
        wv.onFootnoteContent = null
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
     * Cap fling velocity. A very fast fling demands new raster tiles faster than the WebView
     * renderer's shared tile-memory budget can satisfy across the stacked full-height chapter
     * WebViews — Chromium then logs "tile memory limits exceeded, some content may not draw" and
     * drops tiles, which shows as blank regions until they re-rasterize (briefly on a fast GPU,
     * for longer on a slower device). Clamping the launch velocity keeps the per-frame tile demand
     * within budget while still allowing a brisk fling. Tuned against the tile-overflow warning
     * count on a debug device.
     */
    private val maxFlingVelocity =
        (android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity * 0.60f).toInt()

    override fun fling(velocityY: Int) {
        super.fling(velocityY.coerceIn(-maxFlingVelocity, maxFlingVelocity))
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
        openWindowAt(initialHref, initialProgression)
    }

    /**
     * Builds the sliding window with [initialHref] at the top and scrolls to [initialProgression]
     * (or, when [anchorFragment] names an element, to that element) once the target has measured.
     * Used for the first open, for a far TOC/link jump, and to recover after the WebView renderer
     * process is killed (see [onRenderProcessGone] wiring in [appendChapter]).
     */
    private fun openWindowAt(initialHref: String, initialProgression: Float, anchorFragment: String = "") {
        val targetIndex = ContinuousPositionTracker
            .chapterIndexForHref(allChapters.map { it.link.href.toString() }, initialHref)
            .coerceAtLeast(0)

        // Build the full window — behind buffer + target + ahead buffer — up front, with the behind
        // buffer included from the start. The target then sits at window index [targetWindowIndex],
        // and we land on it with the SAME slot-based math the in-window (chapter-map) path uses:
        // scroll to (sum of the measured heights before the target) + the in-chapter offset. This is
        // exact and needs no incremental scroll compensation.
        //
        // Earlier this opened with the target at window-index 0 and filled the behind buffer
        // afterwards via prependChapter's running scrollBy compensation. That async compensation
        // landed imprecisely — a far TOC jump came to rest about a screen above the target (the
        // previous chapter showing, the target's heading pushed to the bottom). Waiting for the
        // behind buffer to measure costs one extra chapter's load before the first paint, which is
        // an acceptable trade for landing exactly where the TOC entry points.
        val behind = minOf(CHAPTERS_BEHIND, targetIndex)
        topIndex = targetIndex - behind
        val targetWindowIndex = behind
        val totalChapters = minOf(behind + 1 + CHAPTERS_AHEAD, allChapters.size - topIndex)

        // Land only once every chapter up to and including the target has reported its real height,
        // so the target's slot.top (the sum of the heights before it) is exact.
        pendingInitialMeasureIndices.clear()
        pendingInitialMeasureIndices.addAll(0..targetWindowIndex)
        pendingInitialScroll = {
            val slot = buildWindow().getOrNull(targetWindowIndex)
            val targetWv = webViews.getOrNull(targetWindowIndex)
            if (slot != null) {
                // With an anchor fragment (TOC/cross-ref target) land on that element inside the
                // resource; otherwise land at the resource top (progression 0) or the saved
                // progression offset (resume).
                if (anchorFragment.isNotEmpty() && targetWv != null) {
                    targetWv.anchorOffsetTopDevicePx(anchorFragment) { anchorOffset ->
                        val within = anchorOffset ?: (initialProgression * slot.height).toInt()
                        scrollTo(0, (slot.top + within).coerceAtLeast(0))
                    }
                } else {
                    scrollTo(0, (slot.top + (initialProgression * slot.height).toInt()).coerceAtLeast(0))
                }
            }
        }

        repeat(totalChapters) { i -> appendChapter(topIndex + i) }

        // Safety net: the scroll above waits for the behind buffer AND the target to measure. If a
        // chapter is slow to load/measure (large resource, slow device, transient renderer hiccup),
        // never leave the window stranded on a stale scroll offset showing a blank — fire the scroll
        // after a short grace period with whatever heights are known. Lands the target at the behind
        // buffer's placeholder height; the index-0 height compensation then corrects it to the exact
        // top once the behind buffer's real height arrives. A no-op if the normal path already fired.
        postDelayed({
            if (pendingInitialScroll != null) {
                val scroll = pendingInitialScroll
                pendingInitialScroll = null
                pendingInitialMeasureIndices.clear()
                scroll?.invoke()
            }
        }, INITIAL_SCROLL_FALLBACK_MS)
    }

    /**
     * Recover after the shared WebView renderer process is gone (Android reclaims it under memory
     * pressure — likelier with very large chapters held in several stacked WebViews). All WebViews
     * become permanently blank, and if [ChapterWebView] did not consume the event the whole app
     * would crash. We capture the current reading position, tear down every (now-dead) WebView, and
     * rebuild the window around that position so the reader restores itself in place instead of
     * showing blank pages. Debounced because the event fires once per live WebView at the same time.
     */
    private fun recoverFromRendererGone() {
        if (rendererRecovering) return
        rendererRecovering = true
        val window = buildWindow()
        val (href, progression) = if (window.isNotEmpty()) {
            ContinuousPositionTracker.locatorAt(scrollY, height, window)
        } else {
            (allChapters.getOrNull(topIndex)?.link?.href?.toString() ?: "") to 0f
        }
        webViews.forEach { it.destroy() }
        webViews.clear()
        measuredHeights.clear()
        container.removeAllViews()
        recycledViews.forEach { it.destroy() }
        recycledViews.clear()
        if (href.isNotEmpty()) openWindowAt(href, progression)
        post { rendererRecovering = false }
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
        // TOC entries / chapter-map segments / internal links may carry a #fragment that the spine
        // hrefs don't have; match on the resource path so the chapter is still found.
        val target = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val targetIndex = ContinuousPositionTracker.chapterIndexForHref(
            allChapters.map { it.link.href.toString() }, href,
        )
        if (targetIndex < 0) return
        val inWindow = targetIndex in topIndex until (topIndex + webViews.size)
        if (inWindow) {
            // Already loaded and measured: scroll straight to it.
            post { scrollToLoadedChapter(target, progression, fragment, smooth = true) }
        } else {
            // Far jump (typical TOC / chapter-map tap): rebuild the window around the target and land
            // via the open path, which defers the scroll until the target's REAL height is known.
            // Scrolling immediately against placeholder heights would misposition and flash blank
            // until measurement settled.
            webViews.forEach { it.destroy() }
            webViews.clear()
            measuredHeights.clear()
            container.removeAllViews()
            recycledViews.forEach { it.destroy() }
            recycledViews.clear()
            openWindowAt(target, progression, fragment)
        }
    }

    /**
     * Scroll the (already loaded) chapter [target] into view. When [fragment] names an element, land
     * on that element (so a TOC/cross-ref anchor lands at the heading, not the resource top). With no
     * fragment, a chapter start ([progression] ~0) is TOP-aligned — centring it would push the
     * previous chapter into the top half ("wrong page"); a mid-chapter position (search hit) is
     * centred so the hit is comfortably visible.
     */
    private fun scrollToLoadedChapter(target: String, progression: Float, fragment: String, smooth: Boolean) {
        val window = buildWindow()
        val slot = window.firstOrNull { it.href.substringBefore('#') == target } ?: return
        fun go(y: Int) {
            val clamped = y.coerceAtLeast(0)
            if (smooth) smoothScrollTo(0, clamped) else scrollTo(0, clamped)
        }
        val wvIndex = webViews.indexOfFirst { it.chapterHref.substringBefore('#') == target }
        if (fragment.isNotEmpty() && wvIndex >= 0) {
            webViews[wvIndex].anchorOffsetTopDevicePx(fragment) { anchorOffset ->
                if (anchorOffset != null) go(slot.top + anchorOffset)
                else go(slot.top + (progression * slot.height).toInt())
            }
        } else {
            val base = slot.top + (progression * slot.height).toInt()
            // Top-align a chapter start; centre a mid-chapter target.
            go(if (progression <= 0.001f) base else base - height / 2)
        }
    }

    /** Scroll one viewport-page forward/backward (wired to the volume keys). */
    fun scrollByPage(forward: Boolean) {
        val delta = ContinuousPositionTracker.pageScrollDelta(height)
        smoothScrollBy(0, if (forward) delta else -delta)
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
        wv.onRenderGone = { recoverFromRendererGone() }
        wv.onInternalLink = { onInternalLinkTapped?.invoke(it) }
        wv.onExternalLink = { onExternalLinkTapped?.invoke(it) }
        wv.readaloudAvailable = readaloudAvailable
        wv.onPlayFromHere = { text -> onPlayFromHereSelection?.invoke(wv.chapterHref, text) }
        wv.onFootnoteContent = { onFootnoteContent?.invoke(it) }
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
        wv.onRenderGone = { recoverFromRendererGone() }
        wv.onInternalLink = { onInternalLinkTapped?.invoke(it) }
        wv.onExternalLink = { onExternalLinkTapped?.invoke(it) }
        wv.readaloudAvailable = readaloudAvailable
        wv.onPlayFromHere = { text -> onPlayFromHereSelection?.invoke(wv.chapterHref, text) }
        wv.onFootnoteContent = { onFootnoteContent?.invoke(it) }
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
     *
     * Deliberately at most one shift per call: each shift's scroll compensation uses the *stored*
     * height of the chapter it removes, which is only accurate once that chapter has measured.
     * Shifting several times in one pass (to chase a fast fling) can remove not-yet-measured
     * chapters, so the compensation drifts and opens a blank gap. One shift per frame keeps the
     * compensation correct; the look-ahead buffer (CHAPTERS_AHEAD) absorbs the lead a fling needs.
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
