package com.riffle.app.feature.reader

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.core.view.doOnNextLayout
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.NoopLogger
import org.readium.r2.shared.publication.Publication

/**
 * The sliding-window state machine behind [ContinuousReaderView].
 *
 * Owns `topIndex`, the live `webViews` list, the parallel `measuredHeights`, the pending-initial-
 * scroll / landing-hold / reapply state, the [ChapterWindowManager] shift decisions, the recycled-
 * WebView pool, and the append/prepend/remove chapter operations. Talks to [NestedScrollView]-flavour
 * scroll primitives via [ContinuousScrollPort] so the algorithm is decoupled from the concrete View —
 * a first step toward JVM-testing the window state in isolation (issue #390).
 *
 * [ContinuousReaderView] retains touch/fling arbitration, `requestChildFocus` / `scrollBy` overrides
 * (the selection-jump fix), the fling cap, and the [android.view.View.computeScroll] override — the
 * pieces that must live on a real [android.view.View]. The View calls [tickLandingHold] each
 * `computeScroll` and [onTouchDown] on `ACTION_DOWN` so the controller can react to those View-only
 * signals without owning them.
 */
internal class ContinuousWindowController(
    private val port: ContinuousScrollPort,
    private val context: Context,
    private val onRawPosition: (href: String, progression: Float) -> Unit,
    /**
     * Publish `viewportHeightPx / measuredHeight` for a chapter when its height first lands
     * or changes. Called from the `onHeightMeasured` callbacks in [appendChapter] and
     * [prependChapter]. Feeds `ContinuousPresenter.feedViewportFraction` (issue #399). MUST
     * NOT be invoked from scroll callbacks — see the flake-avoidance rules in the issue.
     */
    private val onViewportFractionMeasured: (href: String, fraction: Double) -> Unit = { _, _ -> },
    /** Injectable clock so [PageScrollCoalescer]'s validity window is testable without touching
     *  Android SDK time APIs. Defaults to [android.os.SystemClock.uptimeMillis] in production. */
    private val nowMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
) : ContinuousHighlightTarget, ContinuousNavigationView {

    companion object {
        // Window buffers were 3+3 (WINDOW_SIZE=7) with a recycled pool cap of WINDOW_SIZE, so
        // Continuous mode could hold up to 14 stacked ChapterWebViews with fully rasterized tiles
        // (setOffscreenPreRaster=true). On a 1 GB Android 7.1 tablet that is enough to trip the
        // renderer heap and get the app killed with no crash trace. Cut first to 2+2, then to 1+1
        // — the initial-land gate now waits for EVERY window chapter to measure, so each extra
        // buffer chapter directly extends the cold-open spinner time (5 chapters at 1+1 → 3);
        // one behind / one ahead is enough to hide the next chapter boundary from a scroll fling
        // without paying for chapters two removed from the viewport.
        /** Default number of chapters kept behind the viewport midpoint. Full-book continuous
         *  mode uses this; the elided (Highlights-mode) reader raises it — see
         *  [chaptersBehind]. Capped at 1 by memory constraints on Android 7.1 tablets: bigger
         *  buffers OOM the WebView renderer with real EPUB chapters loaded with offscreen
         *  pre-raster. Elided-view chapters are synthesised excerpts, small enough to lift the
         *  cap safely. */
        private const val DEFAULT_CHAPTERS_BEHIND = 1

        /** See [ContinuousReaderView.CHAPTERS_AHEAD]. */
        private const val CHAPTERS_AHEAD = 1

        /** Baseline max detached WebViews retained for reuse across window shifts (full-book
         *  continuous mode: `windowSize == 3`). Pooled views keep the previous page's DOM +
         *  rasterized tiles resident until reuse (recycle() blanks the URL but preRaster still
         *  holds an empty document's tiles), so the cap has to stay tight on the Android 7.1
         *  memory budget. Elided-view mode gets a pool matching its larger `windowSize` via
         *  [recyclePoolMax] — synthetic chapters are small enough to lift the cap safely. */
        internal const val DEFAULT_RECYCLE_POOL_MAX = 2

        /**
         * Grace period after a window (re)build before the initial scroll is forced to fire even if
         * not every required chapter has measured — so a slow/failed measurement can't strand the
         * reader on a blank position. Sized to cover cold-open of the whole 3-chapter window on a
         * low-memory Android 7.1 tablet, where 700 ms routinely elapsed before even the target
         * chapter reported its real height. When the fallback wins, the initial land happens on
         * placeholder-sum coordinates and later target-remeasures hard-scroll again — visible as
         * content "reshuffling" during the load — so keeping it long enough that measurements win
         * in the common case is worth the extra spinner time on very slow devices.
         */
        private const val INITIAL_SCROLL_FALLBACK_MS = 2500L

        /** How long after the initial land to override framework smooth-scroll restoration of a
         *  stale scrollY. */
        private const val LANDING_HOLD_MS = 600L

        /**
         * Fixed animation duration for a volume-key page scroll. Matches the Chromium `behavior:
         * 'smooth'` scroll duration used by paginated/vertical mode via [ScrollBoundaryNavigationContainer]
         * closely enough that rapid presses feel the same in both modes. Also the validity window for
         * [PageScrollCoalescer], so a new press coalesces iff its predecessor is still animating.
         */
        internal const val PAGE_SCROLL_DURATION_MS = 300

        /**
         * Upper bound on the loaded window when [ChapterWindowManager.Decision.AppendOnly] is
         * growing it to escape a fits-in-viewport wall-off. Real front-matter sequences (praise,
         * "selected works from…", title, copyright, dedication, contents, author's note) top out
         * well under this; the cap only exists to prevent a pathological all-tiny-chapters book
         * from loading the entire spine at open. Once one chapter measures larger than the
         * remaining viewport space, AppendOnly stops firing naturally regardless of the cap.
         */
        private const val APPEND_ONLY_MAX_WINDOW = 8

        /**
         * Poll interval for retiring a stale [backwardNavigationIntent] after ACTION_UP. The
         * intent must outlive the finger (a backward fling reaches the top clamp after UP), so
         * it can only be dropped once the scroller comes to rest. One frame (~16 ms) would race
         * the posted [maybeShift] that consumes the intent at the clamp; 150 ms is comfortably
         * after it while still far shorter than any deliberate next gesture.
         */
        private const val BACKWARD_INTENT_IDLE_POLL_MS = 150L

        /**
         * Upper bound on how long the backward-prepend scroll floor may wait for Chromium's
         * visual-state (paint) callback after the chapter has measured. Generous because slow
         * GPUs are exactly the case the paint gate exists for; when it expires the floor lifts
         * and behavior degrades to the pre-gate state (scrolling may briefly show unpainted
         * white).
         */
        private const val PREPEND_PAINT_TIMEOUT_MS = 5_000L
    }

    /** The [LinearLayout] the [ContinuousReaderView] wraps; controller owns and mutates its children. */
    val container: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

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
     * "Play". See [ContinuousReaderView.onPlayFromHereSelection].
     */
    var onPlayFromHereSelection: ((href: String, selectedText: String, evalJs: (String, (String?) -> Unit) -> Unit) -> Unit)? = null

    /**
     * Set once by the parent [ContinuousReaderView] with the VM callback that consumes the
     * source book's computed body `font-family` on chapter load (issue #484). Chapter WebViews
     * created after this is set forward their probe callback here.
     */
    var onBookBodyFont: ((String) -> Unit)? = null

    /** Set once via [install]. */
    private lateinit var binder: ChapterWebViewBinder

    /** All chapters in reading order. Set once via [initialize]. */
    private var allChapters: List<ContinuousReaderView.ChapterEntry> = emptyList()

    /** Current formatting preferences for CSS injection. */
    private var formattingPrefs: FormattingPreferences = FormattingPreferences()

    /** Publication used by [ChapterWebView] to serve EPUB resources via shouldInterceptRequest. */
    private var publication: Publication? = null

    /**
     * Index into [allChapters] of the topmost loaded chapter.
     * The window covers [topIndex .. topIndex + loadedCount - 1] (clamped to list bounds),
     * keeping [CHAPTERS_BEHIND] chapters behind the reader and [CHAPTERS_AHEAD] ahead.
     */
    var topIndex: Int = 0
        private set

    /** Parallel list to the loaded WebViews; index i matches container.getChildAt(i). */
    private val webViews = mutableListOf<ChapterWebView>()

    /**
     * Invoked when the container is first revealed after [initialize] and again after each
     * [recoverFromRendererGone] cycle. Wired from [ContinuousReaderView] to flip a Compose state
     * used by [EpubReaderScreen] to overlay a loading spinner over the still-INVISIBLE container
     * during the measurement gate. Not re-armed on cross-chapter jumps (`smoothTail = true`):
     * those already animate visibly and would look worse under a full-screen spinner.
     */
    internal var onFirstLoadComplete: () -> Unit = {}

    /** Paired with [onFirstLoadComplete]; fired when [recoverFromRendererGone] resets the flag so
     *  the spinner reappears during the rebuild. */
    internal var onFirstLoadRestart: () -> Unit = {}
    private var firstLoadComplete: Boolean = false

    private fun notifyFirstLoadCompleteOnce() {
        if (firstLoadComplete) return
        firstLoadComplete = true
        onFirstLoadComplete()
    }

    /** Wired by [ContinuousReaderView.logger] from the reader ViewModel so decoration-path
     *  diagnostics land in the in-app debug screen (channel [LogChannel.ReaderDecoration]).
     *  Setter propagates to the child [ContinuousDecorationController]. */
    internal var logger: Logger = NoopLogger
        set(value) {
            field = value
            decorations.logger = value
        }

    /** Owns annotation + search decoration state and the apply-to-window loops. */
    private val decorations = ContinuousDecorationController(
        port = object : ContinuousDecorationController.Port {
            override fun forEachLoadedWebView(block: (ChapterWebViewLike) -> Unit) = webViews.forEach(block)
            override fun findLoadedWebView(href: String): ChapterWebViewLike? =
                webViews.firstOrNull { it.chapterHref == href }
            override fun scrollTo(y: Int) = port.scrollTo(y)
            override fun smoothScrollTo(y: Int) = port.smoothScrollTo(y)
            override fun clearLandingHold() = this@ContinuousWindowController.clearLandingHold()
            override fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot> = this@ContinuousWindowController.buildWindow()
            override val viewportHeightPx: Int get() = port.viewportHeightPx
            override val currentScrollY: Int get() = port.currentScrollY
        },
    )

    /**
     * Install a per-chapter Cadence hook that fires whenever a loaded chapter enters the sliding
     * window. See [ContinuousDecorationController.setCadenceOnChapterLoaded]. Called by the reader
     * screen once the Cadence session is bound; null clears the hook.
     */
    fun setCadenceOnChapterLoaded(hook: ((wv: ChapterWebViewLike) -> Unit)?) {
        decorations.setCadenceOnChapterLoaded(hook)
    }

    /**
     * Fan an ADR-0041 Highlights-mode DOM patch out to every loaded chapter WebView in the sliding
     * window. Each patch's JS resolves its target via `data-ann-id` and no-ops on chapters that
     * don't hold the annotation, so the broadcast is safe. Paginated / vertical mode route this
     * through [com.riffle.app.feature.reader.renderer.RendererBridge]; continuous mode must fan
     * out here because the Readium fragment is parked at height=0 and holds no elided DOM.
     */
    override fun applyHighlightDomPatch(patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch) {
        val js = patch.applyJs()
        for (wv in webViews) {
            wv.evaluateJavascript(js, null as ((String?) -> Unit)?)
        }
    }


    /** True while a window-shift operation (removeTop/removeBottom/prependChapter) is in progress. */
    private var shiftInProgress = false

    /** True while a window-shift is scheduled (posted) but not yet executed. */
    private var shiftPending = false

    /** Owns the shift-direction algorithm and the justShiftedForward oscillation guard. */
    private val windowManager = ChapterWindowManager(DEFAULT_CHAPTERS_BEHIND)

    /**
     * Direction of the active manual navigation. A short part-title page can leave the viewport
     * midpoint in the following chapter even at scrollY=0, so geometry cannot distinguish a
     * genuine backward gesture from forward-shift compensation.
     */
    private var backwardNavigationIntent = false

    /**
     * A drag can deliver more ACTION_MOVE events after its first prepend has completed. Those
     * moves still describe the same gesture, so they must not re-arm another prepend while the
     * placeholder-height scroll compensation is settling.
     */
    private var backwardShiftConsumedForTouchGesture = false

    @androidx.annotation.VisibleForTesting
    internal val hasPendingBackwardNavigationIntent: Boolean
        get() = backwardNavigationIntent

    /**
     * Test seam: true once [onTouchDown] has fired after the most recent [openWindowAt]. Drives
     * the regression guard that prevents a late reapply-landing (triggered by an image load
     * completing after initial measurement) from re-arming the landing hold and freezing the
     * reader after the user starts scrolling — the "stuck title page" regression.
     */
    @androidx.annotation.VisibleForTesting
    internal val isReapplyLandingSuperseded: Boolean
        get() = reapplyLandingSuperseded

    /**
     * Test seam: true from every [prependChapter] call until the next [onTouchDown] (after the
     * chapter is measured and painted). With this armed, [backwardPrependScrollFloorY] keeps the
     * scroll floor at the real chapter height through the end of the crossing gesture, preventing
     * the "abrupt jump to Index" regression (2026-08-06).
     */
    @androidx.annotation.VisibleForTesting
    internal val isBoundaryDetentArmed: Boolean
        get() = boundaryDetentArmed

    /**
     * True while the current touch gesture has already been consumed by a backward prepend.
     * [ContinuousReaderView.fling] checks this to swallow the gesture's release-velocity fling:
     * the top clamp ate the gesture, so letting its momentum restart AFTER the prepend's scroll
     * compensation would carry the reader deep past the chapter boundary the prepend just
     * revealed. Cleared on the next ACTION_DOWN (and every window rebuild / explicit nav).
     */
    val suppressGestureFling: Boolean
        get() = backwardShiftConsumedForTouchGesture

    /**
     * Chapters kept behind the viewport before a forward shift fires. Elided-view opens set this
     * to 2+ (see [DEFAULT_CHAPTERS_BEHIND]) so tiny synthetic chapters don't oscillate. MUST be
     * set before [openWindowAt] — the initial window size derives from it and can't be resized
     * mid-window.
     */
    internal var chaptersBehind: Int
        get() = windowManager.chaptersBehind
        set(value) { windowManager.chaptersBehind = value }

    private val windowSize: Int get() = chaptersBehind + 1 + CHAPTERS_AHEAD

    /**
     * Recycled-WebView pool cap, sized to the current window. The class-comment invariant is that
     * the pool cap should equal `windowSize`; when the elided reader raises `chaptersBehind` to 3
     * (windowSize 5), a hard-coded cap of 2 would destroy 2 WebViews on every shift instead of
     * recycling them.
     */
    private val recyclePoolMax: Int get() = maxOf(DEFAULT_RECYCLE_POOL_MAX, windowSize)

    /** True while rebuilding the window after a WebView renderer-process death. */
    private var rendererRecovering = false

    /** Measured content heights for each WebView in the current window. */
    private val measuredHeights = mutableListOf<Int>()

    /**
     * Window indices (0-based) of chapters that must report their real height before the
     * initial scroll fires. Populated in [openWindowAt]; cleared as each chapter measures.
     */
    private val pendingInitialMeasureIndices = mutableSetOf<Int>()

    /**
     * Closure that performs the initial [android.view.View.scrollTo] once all chapters in
     * [pendingInitialMeasureIndices] have reported their real heights.
     */
    private var pendingInitialScroll: (() -> Unit)? = null

    /** The active safety-net fallback [Runnable] posted by [openWindowAt], or null. */
    private var pendingFallbackRunnable: Runnable? = null

    /** Href of the chapter the initial scroll lands on. Stable across window shifts. */
    private var pendingTargetHref: String? = null

    /**
     * The initial-scroll closure, retained when the safety-net fallback fires it BEFORE the target
     * chapter measured. Re-invoked once the target chapter reports its real height so an
     * annotation/resume landing corrects itself instead of resting near the chapter top.
     */
    private var reapplyLandingAfterFallback: (() -> Unit)? = null

    /** Target chapter height used by the last re-applied landing; re-apply again when it changes. */
    private var reapplyTargetLastHeight: Int = -1

    /**
     * True while a smooth-tail initial land is running (set by [openWindowAt] when
     * `smoothTail = true`). Used to suppress `reapplyLandingAfterFallback` — a target-chapter
     * remeasure during the ~250 ms smooth animation would re-invoke the initial-scroll closure
     * and hard-scrollTo mid-animation, chopping the tween. In smoothTail mode we accept a small
     * position offset from late reflow rather than kill the visible motion. Cleared on
     * [onTouchDown] and by any subsequent [navigateTo].
     */
    private var smoothTailInProgress: Boolean = false

    /**
     * True from the first [onTouchDown] after [openWindowAt] until the next [openWindowAt].
     * Guards against already-posted `postLandAt` closures running after the user has touched:
     * a `reapplyLandingAfterFallback` triggered by a late chapter remeasure (e.g. a cover image
     * loading AFTER the initial height measurement) can have a `port.post { postLandAt }` already
     * queued when `onTouchDown` runs. `onTouchDown` nulls `reapplyLandingAfterFallback` to block
     * FUTURE invocations, but cannot cancel the already-posted message. That queued `postLandAt`
     * re-arms `landingHoldTargetY` for 600 ms, pinning the reader to the chapter's open position
     * while `tickLandingHold` reverts every scroll tick — the "stuck title page" symptom.
     * Checking this flag inside the `port.post { }` body aborts any re-apply that slipped through.
     */
    private var reapplyLandingSuperseded = false

    /** Annotation id to focus on initial open. See [ContinuousReaderView.pendingFocusAnnotationId]. */
    private var pendingFocusAnnotationId: String? = null

    /** The scrollY of the most recent initial land, and the deadline (uptime ms) until which any
     *  off-target scroll movement should be reverted. See [ContinuousReaderView.landingHoldTargetY]. */
    private var landingHoldTargetY: Int = -1
    private var landingHoldUntilUptimeMs: Long = 0L

    /** Disarm the landing hold so a deliberate programmatic scroll isn't reverted. */
    private fun clearLandingHold() {
        landingHoldTargetY = -1
        landingHoldUntilUptimeMs = 0L
    }

    /**
     * Placeholder height used before real measurement arrives. See
     * [ContinuousReaderView.placeholderHeight].
     */
    private val placeholderHeight: Int get() = context.resources.displayMetrics.heightPixels

    /** Pool of detached [ChapterWebView]s kept for reuse across window shifts. */
    private val recycledViews = ArrayDeque<ChapterWebView>()

    private fun obtainWebView(): ChapterWebView {
        val recycled = recycledViews.removeFirstOrNull()
        return (recycled ?: ChapterWebView(context)).also { wv ->
            // Route JS console errors/warnings to the ReaderDecoration log channel so the in-app
            // debug screen surfaces DOM-side throws (e.g. #428's createTreeWalker-on-null-body)
            // alongside the Kotlin-side decoration events. Wired here so recycled views also
            // pick up the current logger.
            wv.jsConsoleLogger = logger
        }
    }

    /**
     * Detach [wv] from active use and pool it for reuse (or destroy it if the pool is full).
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
        wv.onCrossReferenceTap = null
        wv.onSelectionActiveChanged = null
        // Release the WebView's DOM + rasterized tiles before pooling. Without this, a pooled view
        // keeps its previous chapter's full-height tile pyramid resident (setOffscreenPreRaster is
        // true) until obtainWebView() eventually replaces it — hundreds of MB across the whole pool
        // on a long book. loadChapter() will replace about:blank on the next reuse.
        wv.stopLoading()
        wv.loadUrl("about:blank")
        if (recycledViews.size < recyclePoolMax) recycledViews.addLast(wv) else wv.destroy()
    }

    /**
     * Wire the coordinator's sinks. Must be called once, from [ContinuousReaderCoordinator.attach],
     * before any chapter is appended/prepended.
     */
    fun install(binder: ChapterWebViewBinder) {
        this.binder = binder
    }

    /**
     * Initialize the window at [initialHref] + [initialProgression].
     */
    fun initialize(
        chapters: List<ContinuousReaderView.ChapterEntry>,
        prefs: FormattingPreferences,
        initialHref: String,
        initialProgression: Float,
        publication: Publication,
        alignToTop: Boolean,
        focusAnnotationId: String?,
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
    }

    /**
     * Builds the sliding window with [initialHref] at the top and scrolls to [initialProgression]
     * once the target has measured. See [ContinuousReaderView] doc — extracted verbatim.
     */
    private fun openWindowAt(
        initialHref: String,
        initialProgression: Float,
        anchorFragment: String = "",
        alignToTop: Boolean = false,
        focusAnnotationId: String? = null,
        /**
         * When true the first initial land pre-scrolls half a viewport short of the target under
         * the still-showing nav-cover, then reveals the container and animates the remaining
         * half-viewport with [ContinuousScrollPort.smoothScrollTo]. Used only by [navigateTo]'s
         * cross-window branch so the "back link" (and any cross-chapter jump that rebuilds the
         * window) arrives with visible motion instead of a hard snap on cover-reveal. Other
         * callers (book open, resume, annotation focus, renderer-gone recovery) keep the hard
         * land — a smooth tail on a cold open would just delay first content by ~300ms with no
         * gesture to justify it.
         */
        smoothTail: Boolean = false,
    ) {
        pendingFallbackRunnable?.let { port.removeCallbacks(it) }
        pendingFallbackRunnable = null
        windowManager.reset()
        backwardNavigationIntent = false
        backwardShiftConsumedForTouchGesture = false
        prependAwaitingMeasure = false
        prependLayoutGeneration = 0
        prependAwaitingLayout = false
        boundaryDetentArmed = false
        backwardFlingFloorY = 0
        releasePaintGate()
        smoothTailInProgress = smoothTail
        reapplyLandingSuperseded = false
        // Cross-window user navigation (smoothTail=true): reset firstLoadComplete so the
        // isFirstLoadComplete state cycles false→true while the new window loads. EpubReaderScreen
        // observes this to keep the nav cover up until the content is visible, preventing the
        // blank-flash + abrupt-jump the user sees when a chapter-map tap fires after the initial
        // landing has already placed the reader at the saved reading position.
        if (smoothTail && firstLoadComplete) {
            firstLoadComplete = false
            onFirstLoadRestart()
        }
        container.visibility = android.view.View.INVISIBLE

        val targetIndex = ContinuousPositionTracker
            .chapterIndexForHref(allChapters.map { it.link.href.toString() }, initialHref)
            .coerceAtLeast(0)

        val initial = ContinuousPositionTracker.initialWindow(
            targetIndex = targetIndex,
            allChaptersSize = allChapters.size,
            chaptersBehind = chaptersBehind,
            windowSize = windowSize,
        )
        topIndex = initial.topIndex
        val targetWindowIndex = initial.targetWindowIndex
        pendingTargetHref = initialHref
        reapplyLandingAfterFallback = null
        reapplyTargetLastHeight = -1
        pendingFocusAnnotationId = focusAnnotationId
        val totalChapters = initial.totalChapters
        pendingInitialMeasureIndices.clear()
        pendingInitialMeasureIndices.addAll(initial.pendingMeasureIndices())
        val targetHref = initialHref
        // Only the FIRST invocation of the pending-initial-scroll closure runs the smooth-tail
        // dance. Subsequent invocations from [reapplyLandingAfterFallback] on target-chapter
        // remeasure are corrective micro-adjustments while the user is already looking at the
        // destination — a smooth animation there would look like the page moved on its own.
        var landCount = 0
        pendingInitialScroll = {
            fun postLandAt(offsetWithinTargetPx: Int?) {
                port.post {
                    val i = webViewIndexFor(targetHref)
                    val slot = i?.let { buildWindow().getOrNull(it) }
                    if (i == null || slot == null) {
                        container.visibility = android.view.View.VISIBLE
                        notifyFirstLoadCompleteOnce()
                        return@post
                    }
                    val y = when {
                        offsetWithinTargetPx != null ->
                            (slot.top + offsetWithinTargetPx).coerceAtLeast(0)
                        alignToTop -> (slot.top + (initialProgression * slot.height).toInt()).coerceAtLeast(0)
                        else -> ContinuousPositionTracker.scrollYForProgression(
                            slot.top, slot.height, initialProgression, port.viewportHeightPx,
                        )
                    }
                    val isFirstLand = landCount == 0
                    landCount++
                    // A re-apply triggered by a late chapter remeasure (e.g. cover image load)
                    // can have this `port.post` queued BEFORE onTouchDown runs. onTouchDown nulls
                    // reapplyLandingAfterFallback for future calls but cannot cancel this message.
                    // Without this guard the queued postLandAt re-arms landingHoldTargetY, pinning
                    // the reader to the open position for 600 ms while the user scrolls — the
                    // "stuck title page" symptom.
                    if (!isFirstLand && reapplyLandingSuperseded) {
                        return@post
                    }
                    port.abortFling()
                    if (smoothTail && isFirstLand) {
                        val pre = ContinuousPositionTracker.preLandY(y, port.viewportHeightPx)
                        port.scrollTo(pre)
                        // Don't arm the landing hold: it would fight the tail animation by
                        // reverting each frame back to `pre` until LANDING_HOLD_MS elapses.
                        landingHoldTargetY = -1
                        landingHoldUntilUptimeMs = 0L
                        // Reveal and start the tween on the SAME animation frame. Previously the
                        // reveal used `postOnAnimation` (next vsync) and the smoothScrollTo used
                        // `port.post` (next Handler drain — typically fires FIRST); the tween
                        // began ~1 frame before the container became VISIBLE, so the user saw a
                        // partial animation from wherever the scroll had already advanced.
                        port.postOnAnimation {
                            container.visibility = android.view.View.VISIBLE
                            notifyFirstLoadCompleteOnce()
                            port.smoothScrollTo(y)
                        }
                    } else {
                        port.scrollTo(y)
                        landingHoldTargetY = y
                        landingHoldUntilUptimeMs = android.os.SystemClock.uptimeMillis() + LANDING_HOLD_MS
                        port.postOnAnimation {
                            container.visibility = android.view.View.VISIBLE
                            notifyFirstLoadCompleteOnce()
                        }
                    }
                }
            }
            val targetWv = webViewIndexFor(targetHref)?.let { webViews.getOrNull(it) }
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
        port.postDelayed(fallback, INITIAL_SCROLL_FALLBACK_MS)
    }

    /**
     * Recover after the shared WebView renderer process is gone. See
     * [ContinuousReaderView.recoverFromRendererGone].
     */
    private fun recoverFromRendererGone() {
        if (rendererRecovering) return
        rendererRecovering = true
        val window = buildWindow()
        val (href, progression) = if (window.isNotEmpty()) {
            ContinuousPositionTracker.locatorAt(port.currentScrollY, port.viewportHeightPx, window)
        } else {
            (allChapters.getOrNull(topIndex)?.link?.href?.toString() ?: "") to 0f
        }
        webViews.forEach { it.destroy() }
        webViews.clear()
        measuredHeights.clear()
        container.removeAllViews()
        recycledViews.forEach { it.destroy() }
        recycledViews.clear()
        // Re-arm the first-load spinner: recovery re-hides the container via openWindowAt and
        // won't reveal until every chapter has measured (up to INITIAL_SCROLL_FALLBACK_MS ms).
        // Without this reset the user sees a blank reader for that gap on a mid-session renderer
        // kill (common on low-memory Android 7.1 tablets — exactly the device the fallback was
        // widened for).
        firstLoadComplete = false
        onFirstLoadRestart()
        if (href.isNotEmpty()) openWindowAt(href, progression)
        port.post { rendererRecovering = false }
    }

    /** Update preferences and re-inject styles + remeasure all loaded chapters. */
    override fun updatePreferences(prefs: FormattingPreferences) {
        if (prefs == formattingPrefs) return
        formattingPrefs = prefs
        val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(prefs)
        webViews.forEach { wv -> wv.reinjectAndRemeasure(styleJs) }
    }

    override fun navigateTo(href: String, progression: Float, alignToTop: Boolean) {
        navigateTo(href, progression, alignToTop, focusAnnotationId = null)
    }

    override fun isTargetInWindow(href: String): Boolean =
        ContinuousPositionTracker.isTargetInWindow(
            hrefs = allChapters.map { it.link.href.toString() },
            targetHref = href,
            topIndex = topIndex,
            loadedChapterCount = webViews.size,
        )

    /**
     * Continuous-mode annotation navigation with mark-precise landing. When [focusAnnotationId] is
     * non-null and the chapter is in the sliding window, the landing anchors on the actual
     * `<mark data-riffle-ann="…">` element's device-Y (via [ChapterWebView.annotationOffsetTopDevicePx])
     * rather than the enclosing paragraph's top. That fixes the "mostly miss" behaviour reported for
     * highlights that live mid- or end-paragraph, where landing the paragraph's TOP at the viewport
     * midpoint pushed the actual highlighted text well below the visible band.
     *
     * When [focusAnnotationId] is set but the mark can't be resolved yet (chapter not measured,
     * decorations not applied), we fall back to the paragraph-based landing — same shape as the
     * open-time `focusAnnotationId` path in [openWindowAt].
     */
    override fun navigateTo(href: String, progression: Float, alignToTop: Boolean, focusAnnotationId: String?) {
        backwardNavigationIntent = false
        backwardShiftConsumedForTouchGesture = false
        // Programmatic navigation overrides any gesture-scoped scroll floor: a leftover
        // backward-fling floor from the previous gesture must not clamp the landing scroll.
        backwardFlingFloorY = 0
        val target = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val targetIndex = ContinuousPositionTracker.chapterIndexForHref(
            allChapters.map { it.link.href.toString() }, href,
        )
        if (targetIndex < 0) return
        val inWindow = targetIndex in topIndex until (topIndex + webViews.size)
        if (inWindow) {
            // The posted landing can execute SECONDS later when the main thread is busy with
            // WebView measure storms (observed 1.8 s on an emulator). If the user has touched
            // the reader in the meantime, they've superseded the navigation — landing anyway
            // yanks the viewport back to a stale target from under their scroll.
            inWindowNavSupersededByTouch = false
            port.post {
                if (inWindowNavSupersededByTouch) return@post
                scrollToLoadedChapter(
                    target, progression, fragment,
                    smooth = true, alignToTop = alignToTop,
                    focusAnnotationId = focusAnnotationId,
                )
            }
        } else {
            webViews.forEach { it.destroy() }
            webViews.clear()
            measuredHeights.clear()
            container.removeAllViews()
            recycledViews.forEach { it.destroy() }
            recycledViews.clear()
            openWindowAt(
                initialHref = target,
                initialProgression = progression,
                anchorFragment = fragment,
                alignToTop = alignToTop,
                focusAnnotationId = focusAnnotationId,
                smoothTail = true,
            )
        }
    }

    /**
     * Resolve Cadence's start-span id inside [chapterHref] against the reader's current viewport.
     *
     * In continuous mode the ChapterWebView holds the full chapter body without ever scrolling
     * itself — the parent [ContinuousReaderView] scrolls, and each WebView has `window.scrollY = 0`.
     * So `window.innerHeight` inside the WebView is the chapter height (potentially thousands of
     * px), not the reader viewport, and a naive `[1..ih)` sweep would return the first `.riffle-cd`
     * anywhere in the chapter. We project the reader viewport into this chapter's DOM
     * coordinates (`viewportTopInChapter = port.currentScrollY - slot.top`) and pass that range
     * into [CadenceDomScript.cadenceStartSpanIdJs] so isVisible/isPreceding operate on the
     * region actually under the user's eyes.
     *
     * Returns the parsed id via [callback], or null when the chapter isn't in the window, the
     * WebView isn't measured yet, or the resolver came back empty.
     */
    fun cadenceStartSpanId(chapterHref: String, callback: (String?) -> Unit) {
        val target = chapterHref.substringBefore('#')
        val slot = buildWindow().firstOrNull { it.href.substringBefore('#') == target }
        val wv = webViews.firstOrNull { it.chapterHref.substringBefore('#') == target }
        if (slot == null || wv == null) {
            callback(null)
            return
        }
        val viewportTop = port.currentScrollY - slot.top
        val viewportHeight = port.viewportHeightPx
        val js = com.riffle.app.feature.reader.cadence.CadenceDomScript.cadenceStartSpanIdJs(
            viewportTopDocPx = viewportTop,
            viewportHeightPx = viewportHeight,
            viewportLeftDocPx = 0,
            viewportWidthPx = null,
        )
        wv.evaluateJavascript(js) { raw ->
            callback(com.riffle.app.feature.reader.cadence.CadenceDomScript.parseCadenceStartId(raw))
        }
    }

    /**
     * Resolve the absolute (parent-viewport) Y of the anchor [fragmentId] within [chapterHref].
     * Returns null via [callback] when the chapter isn't in the current window, the WebView for
     * it isn't measured yet, or the element id can't be found. Used by the cross-reference tap
     * handler to detect in-viewport taps and skip both the scroll and the return-to-position card.
     */
    fun anchorAbsoluteY(chapterHref: String, fragmentId: String, callback: (Int?) -> Unit) {
        val target = chapterHref.substringBefore('#')
        val slot = buildWindow().firstOrNull { it.href.substringBefore('#') == target }
        val wv = webViews.firstOrNull { it.chapterHref.substringBefore('#') == target }
        if (slot == null || wv == null) {
            callback(null)
            return
        }
        wv.anchorOffsetTopDevicePx(fragmentId) { offset ->
            callback(if (offset == null) null else slot.top + offset)
        }
    }

    private fun scrollToLoadedChapter(
        target: String,
        progression: Float,
        fragment: String,
        smooth: Boolean,
        alignToTop: Boolean = false,
        focusAnnotationId: String? = null,
    ) {
        val window = buildWindow()
        val slot = window.firstOrNull { it.href.substringBefore('#') == target } ?: return
        clearLandingHold()
        fun go(y: Int) {
            val clamped = y.coerceAtLeast(0)
            if (smooth) port.smoothScrollTo(clamped) else port.scrollTo(clamped)
        }
        val wvIndex = webViews.indexOfFirst { it.chapterHref.substringBefore('#') == target }
        if (wvIndex < 0) return
        val wv = webViews[wvIndex]

        fun landOnAnchorOrProgression() {
            if (fragment.isNotEmpty()) {
                wv.anchorOffsetTopDevicePx(fragment) { anchorOffset ->
                    val offset = anchorOffset ?: (progression * slot.height).toInt()
                    go(ContinuousPositionTracker.anchorLandingScrollY(slot.top, offset, port.viewportHeightPx, alignToTop))
                }
            } else {
                go(
                    if (alignToTop) slot.top + (progression * slot.height).toInt()
                    else ContinuousPositionTracker.scrollYForProgression(slot.top, slot.height, progression, port.viewportHeightPx)
                )
            }
        }

        // Prefer the actual annotation mark's device-Y over the enclosing paragraph's top: for a
        // highlight in the middle of a long paragraph, landing the paragraph at midpoint puts the
        // highlighted text well below the viewport centre and often off-screen. Reading the mark's
        // rect directly makes the landing pixel-accurate to what the user tapped in the panel.
        if (focusAnnotationId != null) {
            wv.annotationOffsetTopDevicePx(focusAnnotationId) { annOffset ->
                if (annOffset != null) {
                    go(ContinuousPositionTracker.anchorLandingScrollY(slot.top, annOffset, port.viewportHeightPx, alignToTop))
                } else {
                    // Mark not in DOM yet (chapter measured but decorations still applying) — fall
                    // back to the paragraph anchor for now. openWindowAt's re-land loop handles
                    // this precisely on cold-open; mid-session the fallback is close enough that
                    // the user still lands in the right paragraph.
                    landOnAnchorOrProgression()
                }
            }
        } else {
            landOnAnchorOrProgression()
        }
    }

    /** Scroll one viewport-page forward/backward (wired to the volume keys). Rapid presses coalesce
     *  through [pageScrollCoalescer] so each new press extends the in-flight animation's target
     *  rather than restarting from the current (still-animating) position. */
    override fun scrollByPage(forward: Boolean) {
        val delta = ContinuousPositionTracker.pageScrollDelta(port.viewportHeightPx)
        if (delta == 0) return
        // A volume-key page is a fresh navigation just like a new touch gesture: release the
        // boundary detent once the revealed chapter is ready, or volume-only readers would stay
        // pinned at the boundary forever (touch releases it via onTouchDown instead).
        if (!prependAwaitingMeasure && !prependAwaitingLayout && !prependAwaitingPaint) boundaryDetentArmed = false
        backwardShiftConsumedForTouchGesture = false
        backwardNavigationIntent = !forward
        clearLandingHold()
        val signedDelta = if (forward) delta else -delta
        val current = port.currentScrollY
        val target = pageScrollCoalescer.computeTarget(
            currentScrollY = current,
            dy = signedDelta,
            nowMs = nowMs(),
            minScrollY = 0,
            maxScrollY = port.maxScrollY,
        )
        val animation = ContinuousPositionTracker.pageScrollAnimation(
            currentScrollY = current,
            targetScrollY = target,
            density = context.resources.displayMetrics.density,
        ) ?: run {
            if (!forward && target == current) scheduleShiftCheck()
            return
        }
        port.smoothScrollBy(animation.scrollBy, animation.durationMs)
    }

    /** Window = the duration cap rather than the per-press duration: once an animation has
     *  finished, the pending target equals the settled scroll position, so an over-long window
     *  cannot mis-base the next press. */
    private val pageScrollCoalescer =
        PageScrollCoalescer(ContinuousPositionTracker.PAGE_SCROLL_MAX_DURATION_MS.toLong())

    override fun highlightInChapter(href: String, fragmentId: String?, text: String, cssColor: String) {
        decorations.highlightInChapter(href, fragmentId, text, cssColor)
    }

    override fun clearHighlightInChapter(href: String) {
        decorations.clearHighlightInChapter(href)
    }

    override fun applySearchHighlights(state: SearchHighlightsState?) {
        decorations.applySearchHighlights(state)
    }

    override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) {
        decorations.applyAnnotationHighlights(annotationsByHref, onEachApplied = ::onAnnotationHighlightsApplied)
    }

    fun applyFigureBorders(
        cssRules: List<String>,
        svgMatches: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.SvgMatch>,
        rasterMarks: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.RasterMark> = emptyList(),
    ) {
        decorations.applyFigureBorders(cssRules, svgMatches, rasterMarks)
    }

    /** Hook fired once a chapter's `applyAnnotationHighlightsJs` JS finishes. */
    private fun onAnnotationHighlightsApplied(wv: ChapterWebViewLike) {
        val matches = wv.chapterHref == pendingTargetHref
        logger.d(LogChannel.ReaderDecoration) {
            "onAnnotationHighlightsApplied href='${wv.chapterHref}' matchesPendingTarget=$matches pendingTargetHref='$pendingTargetHref'"
        }
        if (!matches) return
        val annotationReland = annotationFocusRelandClosure(
            pendingFocusAnnotationId = pendingFocusAnnotationId,
            chapterHref = wv.chapterHref,
            landOnAnnotation = ::scrollToFocusAnnotation,
        )
        if (annotationReland != null) {
            // Promote the annotation-focus landing to be the re-land closure so subsequent
            // target-height remeasures (typography settle, late image decodes, style re-injection)
            // re-land on the annotation's current offset rather than on the paragraph anchor.
            // Without this, the anchor-based `reapplyLandingAfterFallback` invoked from
            // appendChapter's onHeightMeasured loop yanks the scroll off the annotation whenever
            // the target chapter reflows.
            reapplyLandingAfterFallback = annotationReland
            annotationReland()
            pendingFocusAnnotationId = null
        } else {
            reapplyLandingAfterFallback?.invoke()
        }
    }

    private fun scrollToFocusAnnotation(href: String, id: String) {
        val wv = webViewIndexFor(href)?.let { webViews.getOrNull(it) } ?: return
        wv.annotationOffsetTopDevicePx(id) { annOffset ->
            if (annOffset == null) return@annotationOffsetTopDevicePx
            val i = webViewIndexFor(href) ?: return@annotationOffsetTopDevicePx
            val slot = buildWindow().getOrNull(i) ?: return@annotationOffsetTopDevicePx
            val y = (slot.top + annOffset).coerceAtLeast(0)
            clearLandingHold()
            port.post { port.scrollTo(y) }
        }
    }

    private fun appendChapter(index: Int) {
        val entry = allChapters[index]
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        binder.bind(wv, annotationsAvailable = annotationsAvailable, readaloudAvailable = readaloudAvailable)
        wv.onPlayFromHere = { text, evalJs -> onPlayFromHereSelection?.invoke(wv.chapterHref, text, evalJs) }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val wasPlaceholder = measuredHeights[i] == placeholder
                val oldHeight = measuredHeights[i]
                val delta = measuredPx - oldHeight
                if (wasPlaceholder && i != 0 && delta < 0) {
                    measuredHeights[i] = measuredPx
                    val newMaxScroll = (measuredHeights.sum() - port.viewportHeightPx).coerceAtLeast(0)
                    if (port.currentScrollY > newMaxScroll) {
                        port.abortFling()
                        port.scrollTo(newMaxScroll)
                    }
                } else {
                    measuredHeights[i] = measuredPx
                }
                publishViewportFraction(wv, measuredPx)
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                if (pendingInitialScroll == null && i == 0 && delta != 0 && (delta < 0 || port.currentScrollY >= oldHeight)) {
                    port.scrollBy(delta)
                }

                if (wasPlaceholder && pendingInitialMeasureIndices.remove(i) &&
                    pendingInitialMeasureIndices.isEmpty()
                ) {
                    val scroll = pendingInitialScroll
                    pendingInitialScroll = null
                    scroll?.invoke()
                    // Post a shift check: if the whole initial window measured shorter than one
                    // viewport (short front-matter — e.g. praise / "selected works from…" / title),
                    // scrollY stays pinned at 0 and no onScrollChangeListener will fire to trigger
                    // handleScrollChange, so decide() would never see the wall-off. Post-drain is
                    // the earliest safe moment: all initial heights are known and the initial-
                    // scroll gate has cleared.
                    if (!shiftPending) {
                        shiftPending = true
                        port.post {
                            shiftPending = false
                            maybeShift()
                        }
                    }
                    // In smoothTail mode the closure launched a NestedScrollView smoothScrollTo;
                    // arming the reapply here would let a late target-chapter remeasure fire the
                    // closure again during the 250 ms tween, taking its ELSE branch (hard
                    // port.scrollTo) and chopping the animation. Accept a small position offset
                    // from late reflow rather than kill the visible motion.
                    reapplyLandingAfterFallback = if (smoothTailInProgress) null else scroll
                    val targetIdx = pendingTargetHref?.let { webViewIndexFor(it) } ?: -1
                    reapplyTargetLastHeight = measuredHeights.getOrElse(targetIdx) { measuredPx }
                } else if (webViews.getOrNull(i)?.chapterHref == pendingTargetHref &&
                    reapplyLandingAfterFallback != null &&
                    measuredPx != reapplyTargetLastHeight
                ) {
                    reapplyTargetLastHeight = measuredPx
                    reapplyLandingAfterFallback?.invoke()
                }

                // AppendOnly-chain: an AppendOnly-appended chapter enters at placeholder height
                // (viewport-sized), which temporarily makes fitsInViewport=false and halts further
                // AppendOnly decisions. When it measures back to its actual (short) height the
                // window may fit in the viewport again and need another append — but no
                // onScrollChangeListener fires (scrollY stays at 0), so without this post the
                // chain stalls. Only trigger when wasPlaceholder (first real measurement from
                // placeholder) and the initial-scroll gate has already cleared (so we don't race
                // with the initial-land post above).
                if (wasPlaceholder && pendingInitialScroll == null && pendingInitialMeasureIndices.isEmpty()) {
                    if (!shiftPending) {
                        shiftPending = true
                        port.post {
                            shiftPending = false
                            maybeShift()
                        }
                    }
                }
            }
        }
        wv.onBookBodyFont = { ff -> onBookBodyFont?.invoke(ff) }
        wv.onPageFinished = {
            val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(styleJs)
            wv.evaluateJavascript(SELECTION_SPAN_TRACKER_JS, null as ((String?) -> Unit)?)
            decorations.onChapterLoaded(wv, onAnnotationsApplied = { onAnnotationHighlightsApplied(wv) })
        }
        webViews.add(wv)
        measuredHeights.add(placeholder)
        container.addView(wv, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        wv.loadChapter(entry.link.href.toString(), entry.url, formattingPrefs)
    }

    /**
     * True from a backward prepend until its chapter reports a real measured height. Passed to
     * [ChapterWindowManager.decide] to hold further ShiftBackwards: each prepend enters at a
     * screen-sized blank placeholder, and on a slow device a large chapter takes seconds to load —
     * without this gate a few quick backward pulls stack unmeasured placeholders until every
     * rendered chapter has been evicted and the screen is solid white (field repro 2026-08-04).
     */
    private var prependAwaitingMeasure = false

    /**
     * True from a backward prepend's first real measurement until the LinearLayout completes its
     * layout pass with the new chapter height. The compensating scroll is deferred until then
     * because NestedScrollView's max-scroll boundary is still based on the placeholder height
     * until layout runs — applying port.scrollBy(delta) earlier clips the scroll to the old
     * max-scroll, landing the user deep mid-chapter instead of at the boundary (field repro
     * 2026-08-06: index.html measured to 42 704 px but scrollBy(40 367) only reached scrollY=2793
     * because content height was still 2337 px in the layout). Included in [topSlotStillPlaceholder]
     * so another ShiftBackward cannot fire while the first is still pending layout.
     */
    private var prependAwaitingLayout = false

    /**
     * Monotonically increasing token incremented by each [prependChapter] call (when it registers
     * a [doOnNextLayout]) and by [removeTop] (when it evicts the pending prepend). Each
     * [doOnNextLayout] closure captures the generation at registration time; on firing it compares
     * against the current value and aborts if they differ. This guards against a recycled WebView
     * carrying a stale [doOnNextLayout] listener from a previous prepend: when that wv is
     * re-added to the container for a new prepend, the layout pass fires BOTH the old listener and
     * the new one. The old listener would see `j >= 0` (the wv is now the new prepend's slot) and
     * incorrectly apply the previous chapter's delta and clear [prependAwaitingLayout] for the new
     * prepend — causing an early scroll correction and unblocking [ShiftBackward] before the new
     * compensating scroll fires.
     */
    private var prependLayoutGeneration = 0

    /**
     * True from a backward prepend's first real measurement until Chromium reports the chapter
     * actually PAINTED (visual-state callback), or [PREPEND_PAINT_TIMEOUT_MS] elapses. Layout
     * height lands within ~300 ms even for a 33k-px chapter, but rasterization takes seconds on
     * slow GPUs — lifting the scroll floor at measure time let the very next flick travel deep
     * into a measured-but-unpainted region: a solid white screen that fills in much later
     * (field repro 2026-08-05, reproduced on emulator: landed mid-ch06 at 56% with 10+s of
     * white). The floor therefore holds the reader at the boundary — on painted content —
     * until the revealed chapter is genuinely drawn.
     */
    private var prependAwaitingPaint = false

    /**
     * Boundary detent: armed by every backward prepend, released by the first ACTION_DOWN that
     * arrives once the chapter is measured AND painted. Keeps the scroll floor active through
     * the END of the gesture that crossed the boundary — however fast the chapter measures and
     * paints — so the crossing pull always lands exactly AT the boundary. Without this, on a
     * fast GPU the measure (+~300 ms) and paint could complete mid-gesture, the floor lifted,
     * and the rest of the same pull (or its release fling on older builds) carried the reader
     * several viewports past the boundary — the "abrupt jump" field-reported through 2026-08-05.
     * The next deliberate gesture starts from the boundary and scrolls normally.
     */
    private var boundaryDetentArmed = false

    /** See the in-window branch of [navigateTo]: set by any touch-down so a still-queued
     *  posted landing knows the user has taken over and must not fire. */
    private var inWindowNavSupersededByTouch = false

    /**
     * Floor for the CURRENTLY animating backward fling, armed by [armBackwardFlingFloor] at
     * fling start and cleared on the next touch-down. Caps a ballistic backward fling at one
     * page past the chapter boundary above its starting chapter (see
     * [ContinuousPositionTracker.backwardFlingFloor]) — covering the behind-buffer path where
     * no prepend (and therefore no prepend floor/detent) exists.
     */
    private var backwardFlingFloorY = 0

    /**
     * Floor candidate captured at ACTION_DOWN. The fling only starts at ACTION_UP, by which
     * time the drag portion of the gesture may already have carried the viewport across the
     * boundary into the previous chapter's slot — computing the floor there finds the window's
     * first slot and returns no constraint. The boundary the user perceives is the one above
     * where the GESTURE began.
     */
    private var gestureStartFlingFloorY = 0

    /**
     * Called by [ContinuousReaderView.fling] before starting a backward fling. Latches the
     * gesture-start floor candidate as the active fling floor enforced by the scroll clamp.
     */
    fun armBackwardFlingFloor(): Int {
        backwardFlingFloorY = gestureStartFlingFloorY
        return backwardFlingFloorY
    }

    /** Safety valve: release the paint gate even if the visual-state callback never fires. */
    private var paintGateTimeout: Runnable? = null

    private fun releasePaintGate() {
        prependAwaitingPaint = false
        paintGateTimeout?.let { port.removeCallbacks(it) }
        paintGateTimeout = null
    }

    /**
     * Lowest scrollY the user may reach while a backward prepend is still an unmeasured
     * placeholder — the placeholder's bottom edge, i.e. the chapter boundary. Scrolling INTO the
     * blank placeholder maps those pixels to arbitrary positions once the real height lands (the
     * scroll compensation preserves distance-from-boundary, so blank-dragged pixels resolve to
     * content the user never saw scrolling by — perceived as an abrupt teleport, field repro
     * 2026-08-05 ch07→ch06). [ContinuousReaderView.onOverScrolled] clamps to this floor, holding
     * the reader at the boundary until the chapter renders; the measure's compensating scrollBy
     * then lands them exactly at the boundary and scrolling continues through real content.
     */
    val backwardPrependScrollFloorY: Int
        get() = maxOf(
            ContinuousPositionTracker.backwardPrependScrollFloor(
                topSlotStillPlaceholder =
                    prependAwaitingMeasure || prependAwaitingLayout || prependAwaitingPaint || boundaryDetentArmed,
                topSlotHeightPx = measuredHeights.firstOrNull() ?: 0,
            ),
            backwardFlingFloorY,
        )

    private fun prependChapter(index: Int) {
        val entry = allChapters[index]
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        binder.bind(wv, annotationsAvailable = annotationsAvailable, readaloudAvailable = readaloudAvailable)
        wv.onPlayFromHere = { text, evalJs -> onPlayFromHereSelection?.invoke(wv.chapterHref, text, evalJs) }
        val placeholder = placeholderHeight
        prependAwaitingMeasure = true
        boundaryDetentArmed = true
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            prependAwaitingMeasure = false
            if (i >= 0) {
                publishViewportFraction(wv, measuredPx)
                // Update the layout params to the real height, triggering a layout pass.
                // Do NOT update measuredHeights or compensate scroll yet: NestedScrollView's
                // max-scroll boundary is still based on the placeholder height until layout runs.
                // Calling port.scrollBy(delta) before layout clips the scroll to the old
                // max-scroll — for a large chapter (e.g. a 42 704 px index) this lands the user
                // deep mid-chapter instead of at the boundary (field repro 2026-08-06: expected
                // scrollY=42 704, got 2793 because content height was still 2337 px in layout).
                prependAwaitingLayout = true
                val myGeneration = ++prependLayoutGeneration
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                wv.doOnNextLayout {
                    // Stale-callback guard: if removeTop() evicted this wv and a new prependChapter
                    // reused it, the generation will have advanced past myGeneration. Abort so the
                    // previous chapter's delta is not applied to the new prepend's slot.
                    if (prependLayoutGeneration != myGeneration) return@doOnNextLayout
                    val j = webViews.indexOf(wv)
                    if (j < 0) {
                        prependAwaitingLayout = false
                        return@doOnNextLayout
                    }
                    val delta = measuredPx - measuredHeights[j]
                    measuredHeights[j] = measuredPx
                    prependAwaitingLayout = false
                    if (delta != 0) port.scrollBy(delta)
                }
            }
        }
        wv.onBookBodyFont = { ff -> onBookBodyFont?.invoke(ff) }
        wv.onPageFinished = {
            val styleJs = ContinuousStyleInjector.buildStyleInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(styleJs)
            wv.evaluateJavascript(SELECTION_SPAN_TRACKER_JS, null as ((String?) -> Unit)?)
            decorations.onChapterLoaded(wv, onAnnotationsApplied = { onAnnotationHighlightsApplied(wv) })
        }
        webViews.add(0, wv)
        measuredHeights.add(0, placeholder)
        container.addView(wv, 0, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        port.scrollBy(placeholder)
        wv.loadChapter(entry.link.href.toString(), entry.url, formattingPrefs)
    }

    private fun removeTop() {
        if (webViews.isEmpty()) return
        // The top chapter is the only slot a prepend occupies; evicting it cancels any pending
        // placeholder measurement (recycle() detaches its onHeightMeasured), so the gates must
        // not stay latched or backward navigation stays blocked until the next window rebuild.
        prependAwaitingMeasure = false
        prependLayoutGeneration++ // invalidate any still-registered doOnNextLayout for the evicted wv
        prependAwaitingLayout = false
        boundaryDetentArmed = false
        releasePaintGate()
        val h = measuredHeights.removeAt(0)
        val wv = webViews.removeAt(0)
        container.removeView(wv)
        recycle(wv)
        port.scrollBy(-h)
        topIndex++
    }

    private fun removeBottom() {
        if (webViews.isEmpty()) return
        measuredHeights.removeAt(measuredHeights.lastIndex)
        val wv = webViews.removeAt(webViews.lastIndex)
        container.removeView(wv)
        recycle(wv)
    }

    /**
     * Called by [ContinuousReaderView]'s onScrollChangeListener. Notifies the raw-position sink and
     * schedules a window-shift check on a posted runnable so a compensating scrollBy() doesn't run
     * re-entrantly inside [android.widget.OverScroller]'s computeScroll.
     */
    fun handleScrollChange(scrollY: Int) {
        if (shiftInProgress) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val (href, progression) = ContinuousPositionTracker.locatorAt(scrollY, port.viewportHeightPx, window)
        onRawPosition(href, progression)

        if (!shiftPending) {
            shiftPending = true
            port.post {
                shiftPending = false
                maybeShift()
            }
        }
    }

    private fun maybeShift() {
        if (shiftInProgress || pendingInitialScroll != null) return
        val window = buildWindow()
        if (window.isEmpty()) return
        val sY = port.currentScrollY
        val vh = port.viewportHeightPx
        val (href, _) = ContinuousPositionTracker.locatorAt(sY, vh, window)
        val viewportMidIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        val decision = windowManager.decide(
            sY, viewportMidIndex, window, topIndex, allChapters.size, vh,
            appendOnlyMaxWindow = APPEND_ONLY_MAX_WINDOW,
            backwardNavigationIntent = backwardNavigationIntent,
            topChapterStillPlaceholder = prependAwaitingMeasure || prependAwaitingLayout || prependAwaitingPaint,
        )
        when (decision) {
            ChapterWindowManager.Decision.ShiftBackward -> {
                // Consume the navigation before scroll compensation posts another shift check.
                // Keep the gesture guard armed until the next ACTION_DOWN so later MOVE events
                // from this same pull cannot prepend every earlier resource in succession.
                backwardNavigationIntent = false
                backwardShiftConsumedForTouchGesture = true
                shiftInProgress = true
                // The top clamp consumed this gesture — kill its residual momentum. NestedScrollView
                // flings apply scroller DELTAS in computeScroll, so an in-flight fling composes
                // with the prepend's scrollBy compensations and keeps subtracting after the
                // prepended chapter measures, dragging the reader thousands of px past the
                // boundary into the revealed chapter (field repro 2026-08-03: ch07→ch06 landed
                // ~4 viewports too far back). Backward navigation across a boundary must land AT
                // the boundary. abortFling() covers a fling already running; the view swallows
                // this same gesture's not-yet-started fling via [suppressGestureFling] (the fling
                // often starts only at ACTION_UP, after this branch has already run). The abort
                // also covers the smooth-tail annotation-nav case it was previously limited to.
                port.abortFling()
                removeBottom()
                topIndex--
                prependChapter(topIndex)
                shiftInProgress = false
            }
            ChapterWindowManager.Decision.ShiftForward -> {
                shiftInProgress = true
                removeTop()
                // removeTop calls scrollBy(-h) to keep visible content in place. During smooth-tail
                // annotation nav the OverScroller keeps chasing the original target in the
                // now-shifted layout, landing past the annotation into placeholder chapters →
                // blank screen. Guard on smoothTailInProgress so normal user flings are not
                // interrupted at chapter boundaries.
                if (smoothTailInProgress) port.abortFling()
                val nextIndex = topIndex + webViews.size
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
                shiftInProgress = false
            }
            ChapterWindowManager.Decision.AppendOnly -> {
                // Grow the window without dropping the top: the fits-in-viewport wall-off case
                // (short front-matter chapters totalling less than one viewport) needs more content
                // loaded so the user can scroll off the initial page, but must NOT lose the
                // chapters they opened at. See ChapterWindowManager.Decision.AppendOnly.
                shiftInProgress = true
                val nextIndex = topIndex + webViews.size
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
                shiftInProgress = false
                // Re-post: the newly-appended chapter enters at placeholder height (~viewport),
                // which lifts loadedContentBottom above the viewport and stops further AppendOnly
                // firings. But when it measures back down to its true (short) height, the window
                // may fit in the viewport again and need another append. This re-post catches that
                // via handleScrollChange-style coalescing.
                if (!shiftPending) {
                    shiftPending = true
                    port.post {
                        shiftPending = false
                        maybeShift()
                    }
                }
            }
            ChapterWindowManager.Decision.Hold -> {}
        }
    }

    /**
     * Called from [ContinuousReaderView.computeScroll] every animation frame. Reverts off-target
     * scroll movement while the landing hold is armed — see [ContinuousReaderView.computeScroll]
     * for the full rationale.
     */
    fun tickLandingHold() {
        if (landingHoldTargetY >= 0) {
            if (android.os.SystemClock.uptimeMillis() > landingHoldUntilUptimeMs) {
                landingHoldTargetY = -1
            } else if (port.currentScrollY != landingHoldTargetY) {
                port.abortFling()
                port.scrollTo(landingHoldTargetY)
            }
        }
    }

    /**
     * Called from [ContinuousReaderView.onInterceptTouchEvent] on `ACTION_DOWN`. The user took over:
     * stop auto-re-landing on reflow so we never yank the page out from under a manual scroll.
     */
    fun onTouchDown() {
        inWindowNavSupersededByTouch = true
        // A user gesture supersedes any pending programmatic landing: without this, navigating
        // (TOC/chapter map) and scrolling away within the fallback window (2.5 s) let the
        // fallback fire later and yank the reader back to the stale navigation target —
        // surfaced by the harness once its forward leg raced the in-window nav path, and a
        // long-standing papercut on device. The fallback's second duty — clearing the pending
        // initial-scroll state so the shift machinery unblocks — must still happen, just
        // WITHOUT invoking the stale landing scroll.
        pendingFallbackRunnable?.let { port.removeCallbacks(it) }
        pendingFallbackRunnable = null
        pendingInitialScroll = null
        pendingInitialMeasureIndices.clear()
        backwardFlingFloorY = 0
        gestureStartFlingFloorY = ContinuousPositionTracker.backwardFlingFloor(
            scrollYStart = port.currentScrollY,
            window = buildWindow(),
            viewportHeightPx = port.viewportHeightPx,
        )
        // Release the boundary detent only when the revealed chapter is genuinely ready — a new
        // gesture that starts while it is still loading/painting must stay pinned at the
        // boundary rather than pull into blank content.
        if (!prependAwaitingMeasure && !prependAwaitingLayout && !prependAwaitingPaint) boundaryDetentArmed = false
        reapplyLandingAfterFallback = null
        reapplyLandingSuperseded = true
        pendingFocusAnnotationId = null
        landingHoldTargetY = -1
        landingHoldUntilUptimeMs = 0L
        smoothTailInProgress = false
        backwardNavigationIntent = false
        backwardShiftConsumedForTouchGesture = false
        retireIntentRunnable?.let { port.removeCallbacks(it) }
        retireIntentRunnable = null
        // A manual scroll may leave [port.currentScrollY] far from the coalescer's pending target;
        // reset so the next volume press bases its animation on the user's new position.
        pageScrollCoalescer.reset()
    }

    /**
     * Record finger direction before NestedScrollView changes scrollY. A downward-moving finger
     * means backward reading. At the top clamp Android emits no scroll callback, so explicitly
     * schedule a decision to let the window prepend the previous chapter.
     */
    fun onTouchMove(fingerDeltaY: Float) {
        if (fingerDeltaY == 0f) return
        backwardNavigationIntent = fingerDeltaY > 0f && !backwardShiftConsumedForTouchGesture
        if (backwardNavigationIntent) scheduleShiftCheck()
    }

    /** Self-cancelling idle watch armed by [onTouchUp]; see [BACKWARD_INTENT_IDLE_POLL_MS]. */
    private var retireIntentRunnable: Runnable? = null

    /**
     * Called on ACTION_UP / ACTION_CANCEL. A backward intent must survive the finger lift — a
     * backward fling reaches the scrollY=0 clamp (where the prepend is consumed) well after UP.
     * But an intent that outlives the entire settle without being consumed is stale: leaving it
     * latched lets any later posted decision (e.g. a reflow remeasure) fire a phantom prepend
     * the user never asked for. Watch the scroller and retire the intent once it comes to rest.
     */
    fun onTouchUp() {
        if (!backwardNavigationIntent) return
        retireIntentRunnable?.let { port.removeCallbacks(it) }
        var lastY = port.currentScrollY
        val watch = object : Runnable {
            override fun run() {
                if (retireIntentRunnable !== this || !backwardNavigationIntent) return
                val y = port.currentScrollY
                if (y == lastY) {
                    // Final decision before retiring. Under main-thread contention the posted
                    // shift check from the last scroll change (the one that reached the clamp)
                    // can run AFTER this watch — dropping the intent first would silently
                    // swallow a legitimate boundary prepend (observed as harness flakes on a
                    // contended emulator: pull at the clamp, nothing happens). maybeShift
                    // consumes the intent itself when a prepend fires; clearing afterwards is
                    // then a no-op.
                    maybeShift()
                    backwardNavigationIntent = false
                    retireIntentRunnable = null
                } else {
                    lastY = y
                    port.postDelayed(this, BACKWARD_INTENT_IDLE_POLL_MS)
                }
            }
        }
        retireIntentRunnable = watch
        port.postDelayed(watch, BACKWARD_INTENT_IDLE_POLL_MS)
    }

    private fun scheduleShiftCheck() {
        if (shiftPending) return
        shiftPending = true
        port.post {
            shiftPending = false
            maybeShift()
        }
    }

    /**
     * Called from [ContinuousReaderView.onDetachedFromWindow] — destroy every WebView we hold so
     * they don't leak when the reader screen goes away.
     */
    fun onDetach() {
        webViews.forEach { it.destroy() }
        webViews.clear()
        recycledViews.forEach { it.destroy() }
        recycledViews.clear()
    }

    /**
     * Framework memory-pressure signal (forwarded from [ContinuousReaderView.onTrimMemory]).
     * Dumps the recycled-WebView pool at [android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]
     * or worse — those are idle and their memory is the cheapest to release. Live chapter WebViews
     * are NOT touched here: blanking them with loadUrl would race the wired onPageFinished ->
     * injectStylesAndMeasure pipeline (measures about:blank to ~0 px, collapses the chapter's slot
     * height in the container, breaks scroll math). Live-chapter shrink under pressure needs a
     * proper "detach + reload-on-return" pathway, not a bare loadUrl.
     */
    fun onTrimMemory(level: Int) {
        logger.d(LogChannel.Oom) {
            "[DEBUG-OOM] continuous.onTrimMemory level=$level webViews=${webViews.size} pool=${recycledViews.size}"
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            recycledViews.forEach { it.destroy() }
            recycledViews.clear()
        }
    }

    /**
     * Callback for [ChapterWebViewBinder.onRenderGone]. Public so the [ContinuousReaderView] can
     * pass it into the binder during [install].
     */
    fun onRendererGone() = recoverFromRendererGone()

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
     * Publish `port.viewportHeightPx / measuredPx` for [wv]'s chapter, dropping any case where
     * either side is non-positive (viewport not yet laid out, or the chapter measured to zero).
     * Downstream (VM `putViewportFraction`) applies the per-entry distinct-until-changed guard
     * so repeat calls with the same value do not churn the bookmark combine (issue #399).
     */
    private fun publishViewportFraction(wv: ChapterWebView, measuredPx: Int) {
        val vh = port.viewportHeightPx
        if (vh <= 0 || measuredPx <= 0) return
        val href = wv.chapterHref
        if (href.isEmpty()) return
        onViewportFractionMeasured(href, vh.toDouble() / measuredPx)
    }
}

/**
 * Given the state at `onAnnotationHighlightsApplied`, produce the closure that
 * [ContinuousWindowController] should install as `reapplyLandingAfterFallback` so subsequent
 * target-height remeasures re-land on the annotation's current offset (typography settle, late
 * image decodes, style re-injection can all shift the annotation after the initial land).
 *
 * Returns:
 *  - a closure that invokes [landOnAnnotation] when a focus id is pending — the caller installs
 *    it AND invokes it once immediately; the height-change loop in `appendChapter.onHeightMeasured`
 *    then re-invokes it on every subsequent remeasure of the target chapter until height stabilises.
 *  - `null` when no focus id is pending — the caller keeps the existing paragraph-anchor closure.
 *
 * Extracted as a top-level `internal` function so the decision is JVM-testable:
 * [ContinuousWindowController] requires an Android `Context` to construct.
 */
internal fun annotationFocusRelandClosure(
    pendingFocusAnnotationId: String?,
    chapterHref: String,
    landOnAnnotation: (href: String, id: String) -> Unit,
): (() -> Unit)? {
    val id = pendingFocusAnnotationId ?: return null
    return { landOnAnnotation(chapterHref, id) }
}

/**
 * NestedScrollView-flavour scroll primitives the [ContinuousWindowController] uses without knowing
 * it's talking to a real View. Implemented by [ContinuousReaderView].
 */
internal interface ContinuousScrollPort {
    val viewportHeightPx: Int
    val currentScrollY: Int
    /** Maximum scrollable Y (content height minus viewport, floor 0). Used to clamp coalesced
     *  page-scroll targets so a run of volume presses at end-of-content doesn't leave a phantom
     *  target far past the actual max, which would silently absorb the first reversal press. */
    val maxScrollY: Int
    fun scrollTo(y: Int)
    fun scrollBy(dy: Int)
    fun smoothScrollTo(y: Int)
    fun smoothScrollBy(dy: Int)
    /** Fixed-duration variant used by the volume-key page-scroll path so consecutive presses share a
     *  predictable animation length instead of NestedScrollView's velocity-derived default (which
     *  makes rapid presses stutter as each new animation restarts from the current position). */
    fun smoothScrollBy(dy: Int, durationMs: Int)
    fun abortFling()
    fun post(block: () -> Unit)
    fun postOnAnimation(block: () -> Unit)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun removeCallbacks(r: Runnable)
}
