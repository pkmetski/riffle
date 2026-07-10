package com.riffle.app.feature.reader

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
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
        /** See [ContinuousReaderView.CHAPTERS_BEHIND]. */
        private const val CHAPTERS_BEHIND = 3

        /** See [ContinuousReaderView.CHAPTERS_AHEAD]. */
        private const val CHAPTERS_AHEAD = 3

        /** Total sliding-window size: the reader's chapter plus the behind/ahead buffers. */
        private const val WINDOW_SIZE = CHAPTERS_BEHIND + 1 + CHAPTERS_AHEAD

        /**
         * Grace period after a window (re)build before the initial scroll is forced to fire even if
         * not every required chapter has measured — so a slow/failed measurement can't strand the
         * reader on a blank position.
         */
        private const val INITIAL_SCROLL_FALLBACK_MS = 700L

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
    private val windowManager = ChapterWindowManager(CHAPTERS_BEHIND)

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

    private fun obtainWebView(): ChapterWebView =
        (recycledViews.removeFirstOrNull() ?: ChapterWebView(context)).also { wv ->
            // Route JS console errors/warnings to the ReaderDecoration log channel so the in-app
            // debug screen surfaces DOM-side throws (e.g. #428's createTreeWalker-on-null-body)
            // alongside the Kotlin-side decoration events. Wired here so recycled views also
            // pick up the current logger.
            wv.jsConsoleLogger = logger
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
        if (recycledViews.size < WINDOW_SIZE) recycledViews.addLast(wv) else wv.destroy()
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
        smoothTailInProgress = smoothTail
        container.visibility = android.view.View.INVISIBLE

        val targetIndex = ContinuousPositionTracker
            .chapterIndexForHref(allChapters.map { it.link.href.toString() }, initialHref)
            .coerceAtLeast(0)

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
        pendingInitialMeasureIndices.clear()
        pendingInitialMeasureIndices.addAll(0..targetWindowIndex)
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
                            port.smoothScrollTo(y)
                        }
                    } else {
                        port.scrollTo(y)
                        landingHoldTargetY = y
                        landingHoldUntilUptimeMs = android.os.SystemClock.uptimeMillis() + LANDING_HOLD_MS
                        port.postOnAnimation { container.visibility = android.view.View.VISIBLE }
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
        val target = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val targetIndex = ContinuousPositionTracker.chapterIndexForHref(
            allChapters.map { it.link.href.toString() }, href,
        )
        if (targetIndex < 0) return
        val inWindow = targetIndex in topIndex until (topIndex + webViews.size)
        if (inWindow) {
            port.post {
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
        port.smoothScrollBy(target - current, PAGE_SCROLL_DURATION_MS)
    }

    private val pageScrollCoalescer = PageScrollCoalescer(PAGE_SCROLL_DURATION_MS.toLong())

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

    private fun prependChapter(index: Int) {
        val entry = allChapters[index]
        val wv = obtainWebView()
        publication?.let { wv.setPublication(it) }
        binder.bind(wv, annotationsAvailable = annotationsAvailable, readaloudAvailable = readaloudAvailable)
        wv.onPlayFromHere = { text, evalJs -> onPlayFromHereSelection?.invoke(wv.chapterHref, text, evalJs) }
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val delta = measuredPx - measuredHeights[i]
                measuredHeights[i] = measuredPx
                publishViewportFraction(wv, measuredPx)
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                if (delta != 0) port.scrollBy(delta)
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
        val (href, _) = ContinuousPositionTracker.locatorAt(sY, port.viewportHeightPx, window)
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
                removeTop()
                val nextIndex = topIndex + webViews.size
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
                shiftInProgress = false
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
        reapplyLandingAfterFallback = null
        pendingFocusAnnotationId = null
        landingHoldTargetY = -1
        landingHoldUntilUptimeMs = 0L
        smoothTailInProgress = false
        // A manual scroll may leave [port.currentScrollY] far from the coalescer's pending target;
        // reset so the next volume press bases its animation on the user's new position.
        pageScrollCoalescer.reset()
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
