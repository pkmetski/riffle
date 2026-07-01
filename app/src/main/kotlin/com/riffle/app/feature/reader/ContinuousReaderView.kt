package com.riffle.app.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.OverScroller
import androidx.compose.runtime.mutableStateOf
import androidx.core.widget.NestedScrollView
import com.riffle.core.domain.FormattingPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import kotlin.math.abs

/**
 * Renders the entire book as a single vertical scroll by stacking a sliding window of
 * [WINDOW_SIZE] [ChapterWebView]s inside a [LinearLayout].
 *
 * Scrolling is owned entirely by this [NestedScrollView]; each [ChapterWebView] has its
 * internal scrolling disabled and its height fixed to its measured content height.
 *
 * Window shifting: when [currentChapterIndex] advances past the bottom chapter or retreats
 * past the top chapter, the far end is destroyed and a new chapter is added at the other end.
 * Adding a chapter at the TOP adjusts [scrollY] by the new chapter's height to keep the
 * visible content stable.
 */
internal data class AnnotationHighlight(
    val id: String,
    val text: String,
    val cssColor: String,
    val hasNote: Boolean = false,
    val before: String = "",
    val after: String = "",
)

internal class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs), ContinuousHighlightTarget, ContinuousNavigationView {

    companion object {
        /**
         * Chapters kept loaded behind the reader (for smooth backward scrolling).
         *
         * Must be ≥ (N+1) where N is the maximum run of consecutive chapters whose combined
         * height fits below viewport/2. After a backward shift, scrollBy(placeholder) moves the
         * viewport past all those short chapters; the forward-shift condition (gap > CHAPTERS_BEHIND)
         * then fires and immediately undoes the shift — the user is stuck in a loop.
         *
         * Gap after a backward shift = (number of short chapters above the viewport midpoint) + 1,
         * because topIndex decrements by 1 while viewportMidIndex stays in whichever chapter held
         * it before. In this book ch-62 "В Ерусалим" (200 px) + ch-63 "В Дамаск" (400 px) are both
         * shorter than viewport/2, giving gap = 3 → CHAPTERS_BEHIND must be ≥ 3.
         */
        private const val CHAPTERS_BEHIND = 3

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

        /** How long after the initial land to override framework smooth-scroll restoration of a
         *  stale scrollY (NestedScrollView's mScroller resumes ~50ms after the land scrollTo and
         *  drives the viewport off-target by hundreds of pixels). Cleared early on user touch. */
        private const val LANDING_HOLD_MS = 600L
    }

    data class ChapterEntry(val link: Link, val url: String)

    /** Whether the text-selection menu should offer "Highlight" (books with annotations UI). */
    var annotationsAvailable: Boolean = false
        set(value) {
            field = value
            webViews.forEach { it.annotationsAvailable = value }
        }

    /** Whether the text-selection menu should offer "Play" (readaloud books only). */
    var readaloudAvailable: Boolean = false
        set(value) {
            field = value
            webViews.forEach { it.readaloudAvailable = value }
        }

    /**
     * Called on the main thread with (chapter href, selected text, evalJs) when the user taps
     * "Play". [evalJs] is the WebView's evaluateJavascript so the host can run geometry-based
     * sentence resolution before falling back to text matching. Retained as a View-owned callback
     * (unlike the other per-chapter callbacks, which route through [ChapterWebViewBinder]) because
     * sentence-scoped resolution needs publication state that only the coordinator holds.
     */
    var onPlayFromHereSelection: ((href: String, selectedText: String, evalJs: (String, (String?) -> Unit) -> Unit) -> Unit)? = null

    /** Constructed by [install] once the coordinator supplies the three sinks. Binds every
     *  per-chapter [ChapterWebView] callback except `onInternalLink` and `onPlayFromHere` (see
     *  [ChapterWebViewBinder] doc). */
    private var binder: ChapterWebViewBinder? = null

    /** Set by [install]; invoked from [handleScrollChange] with the raw `(href, progression)` on
     *  every scroll-position update. */
    private var onRawPosition: ((href: String, progression: Float) -> Unit)? = null

    /**
     * Wire this view to the coordinator's sinks. Must be called once, from
     * [ContinuousReaderCoordinator.attach], before any chapter is appended/prepended — the binder
     * it constructs is what [appendChapter] / [prependChapter] use to wire each [ChapterWebView].
     */
    internal fun install(
        navigation: ContinuousNavigationSink,
        links: ContinuousLinkSink,
        annotations: ContinuousAnnotationSink,
        onInternalLink: (href: String) -> Unit,
        onRawPosition: (href: String, progression: Float) -> Unit,
    ) {
        this.onRawPosition = onRawPosition
        binder = ChapterWebViewBinder(
            navigation = navigation,
            links = links,
            annotations = annotations,
            screenRectOf = ::screenRectFor,
            onRenderGone = ::recoverFromRendererGone,
            onInternalLink = onInternalLink,
            onSelectionActiveChanged = ::onChildSelectionActiveChanged,
        )
    }

    /** Screen-space rect of [r] (in [wv]-local device pixels), used by [ChapterWebViewBinder] to
     *  position annotation/highlight popups and mark taps against the actual on-screen location. */
    private fun screenRectFor(wv: ChapterWebViewLike, r: android.graphics.Rect): android.graphics.Rect {
        val loc = IntArray(2)
        (wv as android.view.View).getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0] + r.left, loc[1] + r.top, loc[0] + r.right, loc[1] + r.bottom)
    }

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** All chapters in reading order. Set once via [initialize]. */
    private var allChapters: List<ChapterEntry> = emptyList()

    /** True once [initialize] has been called. Observed by the navigation LaunchedEffect in
     *  EpubReaderScreen to avoid calling [navigateTo] before [allChapters] is populated. */
    val isInitialized = mutableStateOf(false)

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

    /** Owns annotation + search decoration state and the apply-to-window loops; see
     *  [ContinuousDecorationController]. The View retains only the landing/focus machinery that
     *  needs the sliding-window state ([onAnnotationHighlightsApplied]). */
    private val decorations = ContinuousDecorationController(
        port = object : ContinuousDecorationController.Port {
            override fun forEachLoadedWebView(block: (ChapterWebViewLike) -> Unit) = webViews.forEach(block)
            override fun findLoadedWebView(href: String): ChapterWebViewLike? =
                webViews.firstOrNull { it.chapterHref == href }
            override fun scrollTo(y: Int) = this@ContinuousReaderView.scrollTo(0, y)
            override fun smoothScrollTo(y: Int) = this@ContinuousReaderView.smoothScrollTo(0, y)
            override fun clearLandingHold() = this@ContinuousReaderView.clearLandingHold()
            override fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot> = this@ContinuousReaderView.buildWindow()
            override val viewportHeightPx: Int get() = height
            override val currentScrollY: Int get() = scrollY
        },
    )

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

    /** Owns the shift-direction algorithm and the justShiftedForward oscillation guard. */
    private val windowManager = ChapterWindowManager(CHAPTERS_BEHIND)

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

    /** The active safety-net fallback [Runnable] posted by [openWindowAt], or null. Tracked so a
     *  subsequent [openWindowAt] call (e.g. from [navigateTo]) can cancel the stale timer before
     *  it fires against the new window's [pendingInitialScroll]. */
    private var pendingFallbackRunnable: Runnable? = null

    /** Href of the chapter the initial scroll lands on. Stable across window shifts — used to
     *  re-resolve the current window slot at scrollTo time so a forward/backward shift between
     *  [pendingInitialScroll] firing and the deferred [post] draining doesn't lock in pre-shift
     *  coordinates (was: a shift made the captured window index point at the next chapter and
     *  the saved-position restore landed one chapter forward). */
    private var pendingTargetHref: String? = null

    /**
     * The initial-scroll closure, retained when the safety-net fallback fires it BEFORE the target
     * chapter measured (so it may have landed short against a placeholder height). Re-invoked once
     * the target chapter reports its real height so an annotation/resume landing corrects itself
     * instead of resting near the chapter top. Null when the normal (measured) path fired the scroll.
     */
    private var reapplyLandingAfterFallback: (() -> Unit)? = null

    /** Target chapter height used by the last re-applied landing; re-apply again when it changes
     *  (the chapter grows as typography reflow settles) until it stabilises. -1 = none applied yet. */
    private var reapplyTargetLastHeight: Int = -1

    /** Annotation id to focus on initial open. Set by [openWindowAt], consumed by the post-
     *  applyAnnotationHighlightsJs callback in [appendChapter] / [applyAnnotationHighlights] to
     *  scroll to the freshly-created `<mark data-riffle-ann="<id>">` even when the regular reapply
     *  chain has been disarmed (touch event during boot, mark created after height stabilised, etc).
     *  Cleared on first manual touch and on rebuilds. */
    private var pendingFocusAnnotationId: String? = null

    /** The scrollY of the most recent initial land, and the deadline (uptime ms) until which any
     *  off-target scroll movement should be reverted. The framework restarts [NestedScrollView]'s
     *  [OverScroller] after the land (a smooth-scroll restoration of a stale scroll-position from
     *  saved view state — drives the user's content off the saved position by hundreds of pixels);
     *  computeScroll() aborts that scroller and snaps back to the target while the window is open.
     *  Cleared on first user touch and on a fresh window open. */
    private var landingHoldTargetY: Int = -1
    private var landingHoldUntilUptimeMs: Long = 0L

    /** Disarm the landing hold so a deliberate programmatic scroll (volume-key page-turn,
     *  readaloud highlight-follow, in-window TOC nav, post-annotation re-land, …) isn't reverted
     *  to the initial-land target. The hold is meant to fight the framework's stale-state
     *  smooth-scroll restoration, not legitimate scroll requests. */
    private fun clearLandingHold() {
        landingHoldTargetY = -1
        landingHoldUntilUptimeMs = 0L
    }

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
        wv.onHighlight = null
        wv.onAnnotationTap = null
        wv.onAnnotationNoteTap = null
        wv.onPlayFromHere = null
        wv.onFootnoteContent = null
        wv.onSelectionActiveChanged = null
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

    /**
     * Number of child [ChapterWebView]s currently showing a text-selection action mode. The reader
     * suppresses both its early intercept and its disallow-intercept override while this is > 0,
     * so dragging a selection handle vertically extends the selection instead of being stolen as a
     * scroll — which previously fling-scrolled the page mid-selection and broke the highlight flow.
     * Counter (not a flag) so two simultaneous selections — possible during a window-shift recycle
     * race where an old menu's destroy fires after a new one's create — can't underflow the state.
     */
    private var selectionActiveCount = 0

    private fun onChildSelectionActiveChanged(active: Boolean) {
        if (active) selectionActiveCount++
        else if (selectionActiveCount > 0) selectionActiveCount--
    }

    /** Test seam: drives the same counter the production [ChapterWebView] callback does, without
     *  needing to spin up a real WebView selection. */
    @androidx.annotation.VisibleForTesting
    internal fun onSelectionActiveForTest(active: Boolean) = onChildSelectionActiveChanged(active)

    /**
     * Decline to be a nested-scrolling parent for child [ChapterWebView]s.
     *
     * Chromium's [android.webkit.WebView] implements [androidx.core.view.NestedScrollingChild3] and,
     * when it wants to scroll itself (most relevantly: to keep the active text selection or its drag
     * handle visible during a handle drag), calls [dispatchNestedPreScroll] / [dispatchNestedScroll]
     * on its scrolling parent. The inherited [NestedScrollView] default consumes those and scrolls
     * its own viewport — same end result as the rectangle-on-screen path: in continuous mode the
     * page jumps to a far-off chapter in the middle of the user's selection because the WebView's
     * scroll request is expressed in its huge child-content coordinates. Refusing to participate in
     * nested scrolling at the start makes `dispatchNested…` return false in the child, so the
     * WebView falls back to its own (disabled) internal scroll and the page stays put. Our own
     * scroll positioning (window shifts, navigate-to) doesn't go through nested scrolling — it
     * calls [scrollTo]/[scrollBy] directly — so this override doesn't affect normal reading.
     */
    override fun onStartNestedScroll(child: android.view.View, target: android.view.View, axes: Int): Boolean = false

    /**
     * Type-aware nested-scroll override. NestedScrollView's basic 3-arg [onStartNestedScroll]
     * delegates to this 4-arg version with `type = TYPE_TOUCH`; the 4-arg version is also what
     * gets called directly for `TYPE_NON_TOUCH` dispatches (programmatic / fling). Chromium's
     * modern WebView routes its scroll-to-center-selection through nested scrolling with the
     * non-touch type, so blocking only the 3-arg version still lets the page jump on long-press
     * near an edge. Refuse both.
     */
    override fun onStartNestedScroll(child: android.view.View, target: android.view.View, axes: Int, type: Int): Boolean = false

    /**
     * Suppress NestedScrollView's "scroll the focused child into view" inside super.requestChildFocus.
     *
     * The actual mechanism behind the "long-press near the top sometimes makes the page jump to
     * reposition the word closer to the middle" bug, identified via logcat stack traces during a
     * live AVD repro on API 33 / Chromium WebView:
     *
     *   WebView.requestFocus (on long-press completing a selection)
     *     → ContinuousReaderView.requestChildFocus
     *       → super (NestedScrollView).requestChildFocus
     *         → NestedScrollView.scrollToChild      ← synchronous
     *           → scrollBy(0, scrollDelta)          ← the page jump (~233px observed)
     *
     * `scrollToChild` doesn't go through the scroller or smoothScroll — it calls `scrollBy`
     * synchronously, INSIDE the super call. So [abortFling] AFTER super is too late, and blocking
     * [onStartNestedScroll] / [requestChildRectangleOnScreen] doesn't help — this path uses neither.
     *
     * We can't override `scrollToChild` (package-private in androidx.core). Instead we set a flag
     * for the duration of the super.requestChildFocus call and short-circuit [scrollBy] while it's
     * set. Continuous mode's own scrolls call [scrollBy] from elsewhere (window-shift compensation,
     * etc.) and never recurse through requestChildFocus, so they're unaffected.
     */
    private var inRequestChildFocus = false


    /**
     * Cancel the implicit "scroll focused child into view" that [NestedScrollView] queues here.
     *
     * `NestedScrollView.requestChildFocus(child, focused)` calls `scrollToChild(focused)`, which
     * computes a scroll delta to bring the focused descendant into view and starts an internal
     * [android.widget.OverScroller] animation. Then `super.requestChildFocus` propagates focus
     * upward. The animation plays out across the next few frames via [computeScroll].
     *
     * Modern Chromium WebView calls [requestFocus] on itself when a long-press completes a
     * selection. If the selection rect is close to a viewport edge (top/bottom), the queued
     * scroll fires and shifts the page so the just-selected word ends up near the middle —
     * which from the user's perspective is "long-press near the top sometimes makes the page
     * jump to reposition the word." This was the residual mode left after blocking
     * [requestChildRectangleOnScreen] and [onStartNestedScroll], because focus-induced scroll
     * uses the scroller directly rather than either of those APIs.
     *
     * Fix: let super run (preserves focus bookkeeping AND any legitimate intra-frame state),
     * then immediately [abortFling] to clear the scroller's pending animation. The next
     * [computeScroll] reads `mScroller.isFinished() == true` and does not move. Continuous mode's
     * own scrolls go through [scrollTo] / [scrollBy] / [smoothScrollBy] elsewhere (window
     * shifting, initial-scroll, navigateTo) and are NOT queued via [scrollToChild], so this
     * doesn't affect them.
     */
    override fun requestChildFocus(child: android.view.View?, focused: android.view.View?) {
        inRequestChildFocus = true
        try {
            super.requestChildFocus(child, focused)
        } finally {
            inRequestChildFocus = false
        }
    }

    override fun scrollBy(x: Int, y: Int) {
        // Block the synchronous scrollBy that NestedScrollView.scrollToChild calls during
        // super.requestChildFocus — the "scroll-to-center-selection" page jump on long-press.
        if (inRequestChildFocus) return
        super.scrollBy(x, y)
    }

    override fun computeScroll() {
        super.computeScroll()
        // Hold the post-land target against framework smooth-scroll restoration of a stale scroll
        // position. NestedScrollView's mScroller resumes ~50ms after the initial-land scrollTo and
        // drives scrollY hundreds of pixels off-target (finalY = a stale scroll position from view-
        // state save). The drifted value would then be persisted, and the next reopen would land
        // even further off — pushing the chapter forward on every reopen. Window is open until
        // LANDING_HOLD_MS elapses or the user touches the screen.
        if (landingHoldTargetY >= 0) {
            if (android.os.SystemClock.uptimeMillis() > landingHoldUntilUptimeMs) {
                landingHoldTargetY = -1
            } else if (scrollY != landingHoldTargetY) {
                abortFling()
                super.scrollTo(0, landingHoldTargetY)
            }
        }
    }

    /**
     * Block all scroll-into-view requests bubbling up from a child [ChapterWebView].
     *
     * Android's [android.webkit.WebView] aggressively keeps the active text selection (and its drag
     * handle) visible by calling [requestRectangleOnScreen] with the current selection rect. That
     * bubbles to the scrolling parent's [requestChildRectangleOnScreen], which here is the inherited
     * [NestedScrollView] implementation that smooth-scrolls the rect into view. In continuous mode
     * each child WebView is sized to its whole chapter — tens of thousands of px tall — so when the
     * user long-presses a word or drags a selection handle, the WebView passes a rect whose top is
     * far inside the chapter and the parent fling-scrolls there. From the user's perspective the
     * page jumps to an unrelated location at the moment they select, and any in-progress drag is
     * lost — the original "page jumps around while highlighting" bug.
     *
     * The scroll request runs BEFORE the action-mode callback fires (the WebView centres the
     * selection in view, then opens the menu), so gating the override on "is a selection mode
     * active" is too late to be useful; the page has already jumped by the time selectionActiveCount
     * bumps. Continuous mode owns scroll positioning entirely — every legitimate scroll comes from
     * the reader's own navigation/restore code, never from a WebView internal — so it's correct to
     * unconditionally decline child rectangle-on-screen requests here.
     */
    override fun requestChildRectangleOnScreen(
        child: android.view.View,
        rectangle: android.graphics.Rect,
        immediate: Boolean,
    ): Boolean = false

    // ChapterWebViews detect vertical motion and call requestDisallowInterceptTouchEvent(true),
    // which would prevent NestedScrollView from intercepting and owning the scroll — exactly the
    // same bug ScrollBoundaryNavigationContainer solves for Readium WebViews. We are the scroll
    // owner during normal reading, so we never yield the right to intercept — EXCEPT while a child
    // WebView is in text-selection action mode, when the WebView's own selection-handle drag logic
    // must keep ownership of vertical movement (extending the selection, not scrolling the page).
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && selectionActiveCount == 0) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    // Intercept vertical movement as soon as it exceeds a minimal threshold (half the system
    // touch slop). NestedScrollView's default is to wait for a full touch slop before
    // intercepting, which leaves the WebView handling touch for long enough that the user
    // perceives scroll resistance ("fighting"). Intercepting earlier gives us scroll ownership
    // from the very first detectable movement, matching native smooth-scroll feel.
    //
    // Suppressed entirely while a text-selection action mode is active: any vertical movement
    // is the user dragging a selection handle, and intercepting it scrolls the page mid-selection
    // (the original "page jumps around while highlighting" bug). We return false directly instead
    // of falling through to super, because super (NestedScrollView) also intercepts vertical
    // drags past its own touch slop — calling super would still steal the handle drag.
    private var interceptDownY = 0f
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (selectionActiveCount > 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interceptDownY = ev.y
                // The user took over: stop auto-re-landing on reflow so we never yank the page
                // out from under a manual scroll.
                reapplyLandingAfterFallback = null
                pendingFocusAnnotationId = null
                landingHoldTargetY = -1
                landingHoldUntilUptimeMs = 0L
            }
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
        alignToTop: Boolean = true,
        /** When this open was triggered by an annotation tap (openAtCfi flow), the annotation's id.
         *  Lets [openWindowAt] anchor the initial landing against the `<mark data-riffle-ann="<id>">`
         *  decoration's actual on-screen Y once it's been applied — a post-reflow precise landing
         *  instead of char-fraction × measured-WebView-height (which overshoots into trailing
         *  whitespace or undershoots when text-density ≠ char-density). */
        focusAnnotationId: String? = null,
    ) {
        allChapters = chapters
        formattingPrefs = prefs
        this.publication = publication
        val anchorFragment = initialHref.substringAfter('#', "")
        openWindowAt(
            initialHref = initialHref.substringBefore('#'),
            initialProgression = initialProgression,
            anchorFragment = anchorFragment,
            alignToTop = alignToTop,
            focusAnnotationId = focusAnnotationId,
        )
        isInitialized.value = true
    }

    /**
     * Builds the sliding window with [initialHref] at the top and scrolls to [initialProgression]
     * (or, when [anchorFragment] names an element, to that element) once the target has measured.
     * Used for the first open, for a far TOC/link jump, and to recover after the WebView renderer
     * process is killed (see [onRenderProcessGone] wiring in [appendChapter]).
     */
    private fun openWindowAt(
        initialHref: String,
        initialProgression: Float,
        anchorFragment: String = "",
        alignToTop: Boolean = false,
        focusAnnotationId: String? = null,
    ) {
        // Cancel any safety-net timer left over from a previous openWindowAt call so it doesn't
        // fire against this window's pendingInitialScroll.
        pendingFallbackRunnable?.let { removeCallbacks(it) }
        pendingFallbackRunnable = null
        // Clear the oscillation guard so a stale forward-shift flag from the previous position
        // doesn't suppress the first backward check after the window is rebuilt here.
        windowManager.reset()
        // Hide the chapter stack until the initial scroll lands. While chapters mount with
        // placeholder heights and then shrink to their real sizes, NestedScrollView re-clamps
        // scrollY in big visible jumps (e.g. 0 → 460 → 5396) — the user sees the wrong content
        // sliding past before the land snaps to the right spot. Held invisible until [postLandAt]
        // runs the deferred scrollTo and the target slot.height has stabilised.
        container.visibility = android.view.View.INVISIBLE

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
        val initial = ContinuousPositionTracker.initialWindow(
            targetIndex = targetIndex,
            allChaptersSize = allChapters.size,
            chaptersBehind = CHAPTERS_BEHIND,
            windowSize = WINDOW_SIZE,
        )
        topIndex = initial.topIndex
        val targetWindowIndex = initial.targetWindowIndex
        pendingTargetHref = initialHref
        reapplyLandingAfterFallback = null
        reapplyTargetLastHeight = -1
        pendingFocusAnnotationId = focusAnnotationId
        val totalChapters = initial.totalChapters
        // Land only once every chapter up to and including the target has reported its real height,
        // so the target's slot.top (the sum of the heights before it) is exact.
        pendingInitialMeasureIndices.clear()
        pendingInitialMeasureIndices.addAll(0..targetWindowIndex)
        val targetHref = initialHref
        pendingInitialScroll = {
            // Three landing strategies in priority order:
            //  1) annotation-decoration anchor: the `<mark data-riffle-ann="<id>">` exists only
            //     after applyAnnotationHighlightsJs has run inside onPageFinished (post-style-
            //     injection / post-reflow), so its rect reflects the FINAL layout. Exact landing.
            //  2) element-id anchor (#fragment): for TOC/cross-ref targets that point at a real
            //     DOM element. Same recipe but the element is created by the chapter itself.
            //  3) progression × slot.height: char-fraction × measured-WebView-height — works
            //     for the resource-top / resume case but lands inexactly when the highlight sits
            //     in dense text (heading-vs-paragraph density mismatch).
            //
            // The reflow-tracking re-land (see [appendChapter]'s onHeightMeasured) re-fires this
            // closure on every target remeasure, so a cold start where (1) returns null on the
            // first fire (mark not yet decorated) falls back to (2)/(3), and the NEXT remeasure
            // — which is virtually always after applyAnnotationHighlightsJs has run — re-fires
            // and snaps onto the precise mark Y.
            //
            // slot / target WebView are resolved by href INSIDE the deferred [post]: a forward or
            // backward shift can fire between closure invocation and the scrollTo draining (the
            // closure clears [pendingInitialScroll] before posting, so [maybeShift] is no longer
            // gated; a previously-queued scroll handler can trigger a shift via [handleScrollChange]).
            // A captured pre-shift window index would then point at the NEXT chapter, landing the
            // user one chapter forward. Looking up by href is shift-stable.
            fun postLandAt(offsetWithinTargetPx: Int?) {
                post {
                    val i = webViewIndexFor(targetHref)
                    val slot = i?.let { buildWindow().getOrNull(it) }
                    if (i == null || slot == null) {
                        // Target dropped out of the window before this fired — reveal anyway so
                        // the user isn't stuck staring at a blank page.
                        container.visibility = android.view.View.VISIBLE
                        return@post
                    }
                    val y = when {
                        // openWindowAt's initial landing keeps the master-shape "anchor at viewport
                        // top" placement: the alignToTop-aware variant (the same `anchorLandingScrollY`
                        // helper scrollToLoadedChapter routes through) caused the post-flip chrome-
                        // reveal harness to flake — performClick on the reader stopped reaching
                        // ContinuousReaderView's tap detector after a midpoint-shifted landing, the
                        // chain isn't yet understood. annotation-list / search-result landings still
                        // go through scrollToLoadedChapter, which honours alignToTop correctly.
                        offsetWithinTargetPx != null ->
                            (slot.top + offsetWithinTargetPx).coerceAtLeast(0)
                        alignToTop -> (slot.top + (initialProgression * slot.height).toInt()).coerceAtLeast(0)
                        else -> ContinuousPositionTracker.scrollYForProgression(
                            slot.top, slot.height, initialProgression, height,
                        )
                    }
                    abortFling()
                    scrollTo(0, y)
                    // Hold the landing target against framework smooth-scroll restoration of a
                    // stale scrollY from view-state. Without this, NestedScrollView's mScroller
                    // resumes ~50ms after this scrollTo and drives the viewport hundreds of pixels
                    // toward an old saved position — pushing the reader past the actual saved
                    // position on every reopen.
                    landingHoldTargetY = y
                    landingHoldUntilUptimeMs = android.os.SystemClock.uptimeMillis() + LANDING_HOLD_MS
                    // Reveal the chapter stack on the next frame so the first paint after the
                    // visibility change happens against the post-scrollTo position, not the
                    // pre-scrollTo one — eliminates the "page slides into place" flash.
                    postOnAnimation { container.visibility = android.view.View.VISIBLE }
                }
            }
            val targetWv = webViewIndexFor(targetHref)?.let { webViews.getOrNull(it) }
            // The offset returned by the async JS query is only trustworthy if [targetWv] is still
            // serving the target chapter. A window shift between the JS dispatch and its callback
            // can recycle [targetWv] to a different chapter (the closure cleared
            // [pendingInitialScroll], unblocking [maybeShift]), in which case the JS resolved
            // against the wrong DOM. Treat that case as a miss and fall back to progression.
            fun ChapterWebView.offsetIfStillTarget(offset: Int?): Int? =
                if (offset != null && chapterHref == targetHref) offset else null
            fun resolveAnchorThenLand() {
                if (anchorFragment.isNotEmpty() && targetWv != null) {
                    targetWv.anchorOffsetTopDevicePx(anchorFragment) { anchorOffset ->
                        postLandAt(targetWv.offsetIfStillTarget(anchorOffset))
                    }
                } else {
                    postLandAt(null)
                }
            }
            if (focusAnnotationId != null && targetWv != null) {
                targetWv.annotationOffsetTopDevicePx(focusAnnotationId) { annOffset ->
                    val validated = targetWv.offsetIfStillTarget(annOffset)
                    if (validated != null) postLandAt(validated)
                    else resolveAnchorThenLand()
                }
            } else {
                resolveAnchorThenLand()
            }
        }

        repeat(totalChapters) { i -> appendChapter(topIndex + i) }

        // Safety net: the scroll above waits for the behind buffer AND the target to measure. If a
        // chapter is slow to load/measure (large resource, slow device, transient renderer hiccup),
        // never leave the window stranded on a stale scroll offset showing a blank — fire the scroll
        // after a short grace period with whatever heights are known. Lands the target at the behind
        // buffer's placeholder height; the index-0 height compensation then corrects it to the exact
        // top once the behind buffer's real height arrives. A no-op if the normal path already fired.
        //
        // A mid-chapter landing (annotation / resume at progression > 0) fired against a placeholder
        // target height comes to rest SHORT (e.g. near the chapter top) — the index-0 compensation
        // only fixes top-alignment, not the progression offset. So retain the closure and re-apply it
        // once the target chapter reports its real height (see [appendChapter]'s onHeightMeasured).
        val fallback = Runnable {
            pendingFallbackRunnable = null
            if (pendingInitialScroll != null) {
                val scroll = pendingInitialScroll
                pendingInitialScroll = null
                pendingInitialMeasureIndices.clear()
                reapplyLandingAfterFallback = scroll
                scroll?.invoke()
            }
        }
        pendingFallbackRunnable = fallback
        postDelayed(fallback, INITIAL_SCROLL_FALLBACK_MS)
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
    override fun updatePreferences(prefs: FormattingPreferences) {
        if (prefs == formattingPrefs) return
        formattingPrefs = prefs
        val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(prefs)
        webViews.forEach { wv -> wv.reinjectAndRemeasure(styleJs) }
    }

    /**
     * Scroll to [href] at [progression]. Loads the chapter into the window if needed.
     *
     * [alignToTop] must be true when [progression] was measured at content top rather than the
     * viewport midpoint — i.e. for bookmarks and external locators (which use CFI-derived
     * progressions). Leave false for continuous-mode round-trips (resume, return-to-position) where
     * [locatorAt] was the source and the midpoint inverse keeps the position drift-free.
     */
    override fun navigateTo(href: String, progression: Float, alignToTop: Boolean) {
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
            post { scrollToLoadedChapter(target, progression, fragment, smooth = true, alignToTop = alignToTop) }
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
            openWindowAt(target, progression, fragment, alignToTop = alignToTop)
        }
    }

    /**
     * Scroll the (already loaded) chapter [target] into view. When [fragment] names an element, land
     * on that element (so a TOC/cross-ref anchor lands at the heading, not the resource top). With no
     * fragment, a chapter start ([progression] ~0) is TOP-aligned — centring it would push the
     * previous chapter into the top half ("wrong page"); a mid-chapter position (search hit) is
     * centred so the hit is comfortably visible.
     */
    private fun scrollToLoadedChapter(target: String, progression: Float, fragment: String, smooth: Boolean, alignToTop: Boolean = false) {
        val window = buildWindow()
        val slot = window.firstOrNull { it.href.substringBefore('#') == target } ?: return
        clearLandingHold()
        fun go(y: Int) {
            val clamped = y.coerceAtLeast(0)
            if (smooth) smoothScrollTo(0, clamped) else scrollTo(0, clamped)
        }
        val wvIndex = webViews.indexOfFirst { it.chapterHref.substringBefore('#') == target }
        if (fragment.isNotEmpty() && wvIndex >= 0) {
            webViews[wvIndex].anchorOffsetTopDevicePx(fragment) { anchorOffset ->
                val offset = anchorOffset ?: (progression * slot.height).toInt()
                go(ContinuousPositionTracker.anchorLandingScrollY(slot.top, offset, height, alignToTop))
            }
        } else {
            // Top-align a chapter start; centre a mid-chapter target (inverse of locatorAt).
            // When alignToTop, skip the half-viewport offset — the progression is content-top-relative.
            go(
                if (alignToTop) slot.top + (progression * slot.height).toInt()
                else ContinuousPositionTracker.scrollYForProgression(slot.top, slot.height, progression, height)
            )
        }
    }

    /** Scroll one viewport-page forward/backward (wired to the volume keys). */
    override fun scrollByPage(forward: Boolean) {
        val delta = ContinuousPositionTracker.pageScrollDelta(height)
        clearLandingHold()
        smoothScrollBy(0, if (forward) delta else -delta)
    }

    /** Inject a readaloud highlight for the sentence with [text] in the chapter matching [href] and scroll it into view. */
    override fun highlightInChapter(href: String, text: String, cssColor: String) {
        decorations.highlightInChapter(href, text, cssColor)
    }

    /** Clear any active readaloud highlight in the chapter at [href]. */
    override fun clearHighlightInChapter(href: String) {
        decorations.clearHighlightInChapter(href)
    }

    override fun applySearchHighlights(state: SearchHighlightsState?) {
        decorations.applySearchHighlights(state)
    }

    /**
     * Apply persisted annotation highlights to all currently loaded chapters and remember the
     * state so that chapters entering the sliding window later (via [appendChapter] /
     * [prependChapter]) automatically receive their marks in [onPageFinished].
     */
    override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) {
        decorations.applyAnnotationHighlights(annotationsByHref, onEachApplied = ::onAnnotationHighlightsApplied)
    }

    /**
     * Hook fired by [appendChapter] / [prependChapter] / [applyAnnotationHighlights] once a
     * chapter's `applyAnnotationHighlightsJs` JS finishes — i.e. once `<mark data-riffle-ann="<id>">`
     * is in the DOM. Re-fires the armed initial-landing closure AND scrolls to the freshly-rendered
     * annotation mark if one was queued for focus.
     *
     * Both operations are gated on the WebView still being the pending-target chapter (the window
     * could have shifted while the JS was in flight) so they're together in one guarded helper —
     * call sites just invoke this and don't need to repeat the index check.
     */
    private fun onAnnotationHighlightsApplied(wv: ChapterWebViewLike) {
        if (wv.chapterHref != pendingTargetHref) return
        reapplyLandingAfterFallback?.invoke()
        scrollToPendingFocusAnnotation(wv.chapterHref)
    }

    /** Once the target chapter's `<mark data-riffle-ann="<id>">` exists in the DOM, scroll to it.
     *  Independent of [reapplyLandingAfterFallback] so it still fires when the reapply chain has
     *  been disarmed (touch event, height stabilised before the mark was created). Clears
     *  [pendingFocusAnnotationId] once landed so it doesn't replay on later annotation refreshes.
     *  Re-looked-up by [href] (rather than taking the [ChapterWebView] directly) because
     *  [annotationOffsetTopDevicePx] is a [ChapterWebView]-only capability not on the narrower
     *  [ChapterWebViewLike] surface the decoration controller's callbacks pass through. */
    private fun scrollToPendingFocusAnnotation(href: String) {
        val id = pendingFocusAnnotationId ?: return
        val wv = webViewIndexFor(href)?.let { webViews.getOrNull(it) } ?: return
        wv.annotationOffsetTopDevicePx(id) { annOffset ->
            if (annOffset == null) return@annotationOffsetTopDevicePx
            // Resolve by href, not by index — a window shift between the JS in-flight and this
            // callback would otherwise look up the wrong slot.
            val i = pendingTargetHref?.let { webViewIndexFor(it) } ?: return@annotationOffsetTopDevicePx
            val slot = buildWindow().getOrNull(i) ?: return@annotationOffsetTopDevicePx
            val y = (slot.top + annOffset).coerceAtLeast(0)
            // This is a deliberate precise re-land onto the freshly-decorated mark; the initial
            // land's hold (which fights the framework's stale-state restoration) would otherwise
            // revert this scrollTo on the next computeScroll tick. Clear so the precise mark Y wins.
            clearLandingHold()
            post { scrollTo(0, y) }
            pendingFocusAnnotationId = null
        }
    }

    // ── private ────────────────────────────────────────────────────────────────

    private fun appendChapter(index: Int) {
        val entry = allChapters[index]
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        binder?.bind(wv, annotationsAvailable = annotationsAvailable, readaloudAvailable = readaloudAvailable)
        wv.onPlayFromHere = { text, evalJs -> onPlayFromHereSelection?.invoke(wv.chapterHref, text, evalJs) }
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
                    // The first real height is often PRE-reflow: typography injection then grows the
                    // chapter, which would leave a mid-chapter (annotation/resume) landing short. Arm
                    // a re-land that tracks the target chapter's height until it stabilises so the
                    // landing follows the reflow. Disarmed on the first manual touch.
                    reapplyLandingAfterFallback = scroll
                    val targetIdx = pendingTargetHref?.let { webViewIndexFor(it) } ?: -1
                    reapplyTargetLastHeight = measuredHeights.getOrElse(targetIdx) { measuredPx }
                } else if (webViews.getOrNull(i)?.chapterHref == pendingTargetHref &&
                    reapplyLandingAfterFallback != null &&
                    measuredPx != reapplyTargetLastHeight
                ) {
                    // Re-land on EACH target remeasure — the chapter keeps growing as typography
                    // reflow settles after style injection (and the safety-net fallback may have fired
                    // the first landing against a placeholder height) — so a mid-chapter annotation/
                    // resume position tracks the final height instead of resting near the chapter top.
                    // Settles once the height stops changing; disarmed on the first manual touch.
                    reapplyTargetLastHeight = measuredPx
                    reapplyLandingAfterFallback?.invoke()
                }
            }
        }
        wv.onPageFinished = {
            val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(styleJs)
            wv.evaluateJavascript(SELECTION_SPAN_TRACKER_JS, null as ((String?) -> Unit)?)
            // Re-fire the armed landing closure on completion: applyAnnotationHighlightsJs inserts
            // the `<mark data-riffle-ann="<id>">` whose rect is what [annotationOffsetTopDevicePx]
            // looks for. The first pendingInitialScroll fire usually happens BEFORE this JS
            // completes (height-measurement reflow triggers it earlier), so the annotation-mark
            // query in that first fire returns null and falls back to anchor/progression.
            // Re-firing the closure here — once the mark is in the DOM — lets the mark query
            // succeed and re-land precisely; and the explicit [scrollToPendingFocusAnnotation]
            // covers the case where reapply has been disarmed (touch event during boot, height
            // stabilised before the mark was created, etc).
            decorations.onChapterLoaded(wv, onAnnotationsApplied = { onAnnotationHighlightsApplied(wv) })
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
        binder?.bind(wv, annotationsAvailable = annotationsAvailable, readaloudAvailable = readaloudAvailable)
        wv.onPlayFromHere = { text, evalJs -> onPlayFromHereSelection?.invoke(wv.chapterHref, text, evalJs) }
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
            wv.evaluateJavascript(SELECTION_SPAN_TRACKER_JS, null as ((String?) -> Unit)?)
            // Same re-land-on-completion hook as in appendChapter.onPageFinished.
            decorations.onChapterLoaded(wv, onAnnotationsApplied = { onAnnotationHighlightsApplied(wv) })
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
        onRawPosition?.invoke(href, progression)

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
        if (shiftInProgress || pendingInitialScroll != null) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val sY = scrollY
        val (href, _) = ContinuousPositionTracker.locatorAt(sY, height, window)
        val viewportMidIndex = allChapters.indexOfFirst { it.link.href.toString() == href }

        val decision = windowManager.decide(sY, viewportMidIndex, window, topIndex, allChapters.size)
        when (decision) {
            ChapterWindowManager.Decision.ShiftBackward -> {
                shiftInProgress = true
                removeBottom()
                topIndex--
                prependChapter(topIndex)
                shiftInProgress = false
            }
            ChapterWindowManager.Decision.ShiftForward -> {
                shiftInProgress = true
                removeTop() // topIndex already incremented inside removeTop()
                val nextIndex = topIndex + webViews.size
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
                shiftInProgress = false
            }
            ChapterWindowManager.Decision.Hold -> {}
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
