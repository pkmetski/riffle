package com.riffle.app.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.OverScroller
import androidx.compose.runtime.mutableStateOf
import androidx.core.widget.NestedScrollView
import com.riffle.core.domain.FormattingPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import kotlin.math.abs

/**
 * Renders the entire book as a single vertical scroll by stacking a sliding window of chapter
 * WebViews. Scroll owned entirely by this [NestedScrollView].
 *
 * As of issue #390, the sliding-window state machine (topIndex, webViews, measuredHeights,
 * pendingInitialScroll, landing-hold, window shifts, renderer recovery) lives in
 * [ContinuousWindowController]. This View retains the NestedScrollView-specific responsibilities:
 * touch/fling arbitration, requestChildFocus / scrollBy overrides (the selection-jump fix), the
 * fling cap, [computeScroll] (tickling the controller's landing-hold), and hosting the container.
 */
internal data class AnnotationHighlight(
    val id: String,
    val text: String,
    val cssColor: String,
    val hasNote: Boolean = false,
    val before: String = "",
    val after: String = "",
    /**
     * Highlights-mode (ADR 0041): when true, the injected `<mark>` MUST NOT carry a click listener.
     * Tap dispatch is owned by the accent-bar span baked into the synthesised HTML by
     * [com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory] — a `<mark>` listener
     * here would let a tap on the text itself open the highlight menu, which the annotations view
     * explicitly disallows.
     */
    val suppressMarkClick: Boolean = false,
)

internal class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs), ContinuousHighlightTarget, ContinuousNavigationView {

    /** Wired from the reader ViewModel; forwards to [ContinuousWindowController.logger] so the
     *  decoration path logs to [com.riffle.core.logging.LogChannel.ReaderDecoration]. */
    internal var logger: com.riffle.core.logging.Logger = com.riffle.core.logging.NoopLogger
        set(value) {
            field = value
            controller.logger = value
        }

    data class ChapterEntry(val link: Link, val url: String)

    private val port = object : ContinuousScrollPort {
        override val viewportHeightPx: Int get() = height
        override val currentScrollY: Int get() = scrollY
        // computeVerticalScrollRange/Extent are @RestrictedApi on NestedScrollView; the equivalent
        // for a NestedScrollView with a single vertical LinearLayout child is (child.height - viewport).
        override val maxScrollY: Int get() =
            ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)
        override fun scrollTo(y: Int) = this@ContinuousReaderView.scrollTo(0, y)
        override fun scrollBy(dy: Int) = this@ContinuousReaderView.scrollBy(0, dy)
        override fun smoothScrollTo(y: Int) = this@ContinuousReaderView.smoothScrollTo(0, y)
        override fun smoothScrollBy(dy: Int) = this@ContinuousReaderView.smoothScrollBy(0, dy)
        override fun smoothScrollBy(dy: Int, durationMs: Int) =
            this@ContinuousReaderView.smoothScrollBy(0, dy, durationMs)
        override fun abortFling() = this@ContinuousReaderView.abortFling()
        override fun post(block: () -> Unit) { this@ContinuousReaderView.post(block) }
        override fun postOnAnimation(block: () -> Unit) { this@ContinuousReaderView.postOnAnimation(block) }
        override fun postDelayed(r: Runnable, delayMs: Long) { this@ContinuousReaderView.postDelayed(r, delayMs) }
        override fun removeCallbacks(r: Runnable) { this@ContinuousReaderView.removeCallbacks(r) }
    }

    private val controller = ContinuousWindowController(
        port = port,
        context = context,
        onRawPosition = { href, progression -> onRawPosition?.invoke(href, progression) },
        onViewportFractionMeasured = { href, fraction -> onViewportFractionMeasured?.invoke(href, fraction) },
    )

    /** Whether the text-selection menu should offer "Highlight" (books with annotations UI). */
    var annotationsAvailable: Boolean
        get() = controller.annotationsAvailable
        set(value) { controller.annotationsAvailable = value }

    /** Whether the text-selection menu should offer "Play" (readaloud books only). */
    var readaloudAvailable: Boolean
        get() = controller.readaloudAvailable
        set(value) { controller.readaloudAvailable = value }

    /**
     * Called on the main thread with (chapter href, selected text, evalJs) when the user taps
     * "Play". [evalJs] is the WebView's evaluateJavascript so the host can run geometry-based
     * sentence resolution before falling back to text matching.
     */
    var onPlayFromHereSelection: ((href: String, selectedText: String, evalJs: (String, (String?) -> Unit) -> Unit) -> Unit)?
        get() = controller.onPlayFromHereSelection
        set(value) { controller.onPlayFromHereSelection = value }

    /**
     * Called on the main thread with the raw JSON payload emitted by figure-tap.js when the user
     * taps a figure. The host parses it via [FigureTapMessageParser] and pushes the result into
     * the ViewModel's figureZoom state, causing [FigureZoomOverlay] to open above the reader.
     */
    var onFigureTap: ((payload: String) -> Unit)? = null

    /**
     * Called on the main thread with the already-parsed long-press payload emitted by
     * figure-tap.js's `touchstart` listener, plus the figure's on-screen anchor rect (translated
     * from the payload's CSS-px rect by [ChapterWebViewBinder]). Set by the reader screen to
     * [EpubReaderViewModel.onFigureLongPress].
     */
    var onFigureLongPress: ((payload: FigureLongPressPayload, anchorRect: androidx.compose.ui.unit.IntRect) -> Unit)? = null

    /**
     * Called on the main thread when the last active text-selection action mode ends (either
     * via a menu-item finish() or a tap-outside dismissal). Used by the reader to force-re-apply
     * immersive mode: after ActionMode dismissal the OS leaves the system bars in a "transparent
     * overlay" state — layout stays fullscreen so the [ImmersiveModeState] topInset watcher never
     * fires (inset stays at 0), but the bars are drawn semi-visibly on top of the reader. A forced
     * re-hide restores true immersive.
     */
    var onSelectionEnded: (() -> Unit)? = null

    /** Set by [install]; invoked by the controller with the raw `(href, progression)` on
     *  every scroll-position update. */
    private var onRawPosition: ((href: String, progression: Float) -> Unit)? = null

    /**
     * Set by [install]; invoked by the controller with `(href, viewportFraction)` whenever a
     * chapter's measured height first lands or changes. Feeds `ContinuousPresenter.feedViewportFraction`
     * for the bookmark eps window (issue #399). Emitted from measurement callbacks only —
     * never from scroll.
     */
    private var onViewportFractionMeasured: ((href: String, fraction: Double) -> Unit)? = null

    /** True once [initialize] has been called. Observed by the navigation LaunchedEffect in
     *  EpubReaderScreen to avoid calling [navigateTo] before the window is populated. */
    val isInitialized = mutableStateOf(false)

    /**
     * Wire this view to the coordinator's sinks. Must be called once, from
     * [ContinuousReaderCoordinator.attach], before any chapter is appended/prepended.
     */
    internal fun install(
        navigation: ContinuousNavigationSink,
        links: ContinuousLinkSink,
        annotations: ContinuousAnnotationSink,
        onInternalLink: (href: String) -> Unit,
        onCrossReference: (chapterHref: String, fragmentId: String) -> Unit,
        onRawPosition: (href: String, progression: Float) -> Unit,
        onViewportFractionMeasured: (href: String, fraction: Double) -> Unit = { _, _ -> },
    ) {
        this.onRawPosition = onRawPosition
        this.onViewportFractionMeasured = onViewportFractionMeasured
        val binder = ChapterWebViewBinder(
            navigation = navigation,
            links = links,
            annotations = annotations,
            screenRectOf = ::screenRectFor,
            onRenderGone = { controller.onRendererGone() },
            onInternalLink = onInternalLink,
            onCrossReference = onCrossReference,
            onSelectionActiveChanged = ::onChildSelectionActiveChanged,
            onFigureTap = { payload -> onFigureTap?.invoke(payload) },
            onFigureLongPress = { payload, anchorRect -> onFigureLongPress?.invoke(payload, anchorRect) },
        )
        controller.install(binder)
    }

    /** Screen-space rect of [r] (in [wv]-local device pixels), used by [ChapterWebViewBinder] to
     *  position annotation/highlight popups and mark taps against the actual on-screen location. */
    private fun screenRectFor(wv: ChapterWebViewLike, r: android.graphics.Rect): android.graphics.Rect {
        val loc = IntArray(2)
        (wv as android.view.View).getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0] + r.left, loc[1] + r.top, loc[0] + r.right, loc[1] + r.bottom)
    }

    init {
        addView(controller.container, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            controller.handleScrollChange(scrollY)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller.onDetach()
    }

    /**
     * Number of child [ChapterWebView]s currently showing a text-selection action mode. The reader
     * suppresses both its early intercept and its disallow-intercept override while this is > 0.
     * Counter (not a flag) so two simultaneous selections — possible during a window-shift recycle
     * race — can't underflow the state.
     */
    private var selectionActiveCount = 0

    /**
     * Called on the main thread with the current selection-active state whenever it transitions
     * (i.e. on every 0↔1 boundary of [selectionActiveCount]). Distinct from [onSelectionEnded],
     * which only fires on the falling edge — the reader uses this to also pause Auto-Scroll /
     * Cadence the moment a selection begins.
     */
    var onSelectionActiveChanged: ((Boolean) -> Unit)? = null

    private fun onChildSelectionActiveChanged(active: Boolean) {
        val wasActive = selectionActiveCount > 0
        if (active) {
            selectionActiveCount++
        } else if (selectionActiveCount > 0) {
            selectionActiveCount--
            if (selectionActiveCount == 0) onSelectionEnded?.invoke()
        }
        val nowActive = selectionActiveCount > 0
        if (nowActive != wasActive) onSelectionActiveChanged?.invoke(nowActive)
    }

    /** Test seam: drives the same counter the production [ChapterWebView] callback does, without
     *  needing to spin up a real WebView selection. */
    @androidx.annotation.VisibleForTesting
    internal fun onSelectionActiveForTest(active: Boolean) = onChildSelectionActiveChanged(active)

    /**
     * Decline to be a nested-scrolling parent for child [ChapterWebView]s. See historical comment
     * in git — Chromium WebView's dispatchNestedPreScroll would otherwise scroll our viewport to
     * keep the active selection visible, jumping the page mid-highlight.
     */
    override fun onStartNestedScroll(child: android.view.View, target: android.view.View, axes: Int): Boolean = false

    /** Type-aware nested-scroll override; refuse the non-touch path too. */
    override fun onStartNestedScroll(child: android.view.View, target: android.view.View, axes: Int, type: Int): Boolean = false

    /**
     * Suppress NestedScrollView's "scroll the focused child into view" inside super.requestChildFocus.
     * Modern Chromium WebView calls requestFocus on itself when a long-press completes a selection;
     * NestedScrollView.scrollToChild then synchronously scrollBy()s to reposition the word toward
     * the middle — the "page jump on long-press near an edge" bug.
     *
     * We can't override scrollToChild (package-private). Instead we set a flag for the duration of
     * super.requestChildFocus and short-circuit [scrollBy] while it's set.
     */
    private var inRequestChildFocus = false

    override fun requestChildFocus(child: android.view.View?, focused: android.view.View?) {
        inRequestChildFocus = true
        try {
            super.requestChildFocus(child, focused)
        } finally {
            inRequestChildFocus = false
        }
    }

    override fun scrollBy(x: Int, y: Int) {
        if (inRequestChildFocus) return
        super.scrollBy(x, y)
    }

    override fun computeScroll() {
        super.computeScroll()
        controller.tickLandingHold()
    }

    /**
     * Block all scroll-into-view requests bubbling up from a child [ChapterWebView]. WebView passes
     * its whole-chapter-tall content rect when tracking a selection; NestedScrollView's default
     * would fling us there. Continuous mode owns scroll positioning entirely.
     */
    override fun requestChildRectangleOnScreen(
        child: android.view.View,
        rectangle: android.graphics.Rect,
        immediate: Boolean,
    ): Boolean = false

    // ChapterWebViews detect vertical motion and call requestDisallowInterceptTouchEvent(true).
    // We are the scroll owner during normal reading — never yield the right to intercept, EXCEPT
    // while a child WebView is in text-selection action mode, when the WebView's own selection-
    // handle drag logic must keep ownership of vertical movement.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && selectionActiveCount == 0) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    // Intercept vertical movement past half the system touch slop (default is full slop — leaves
    // the WebView owning touch long enough to read as scroll resistance). Suppressed entirely
    // while a text-selection action mode is active.
    private var interceptDownY = 0f
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (selectionActiveCount > 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interceptDownY = ev.y
                controller.onTouchDown()
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.y - interceptDownY) > touchSlop / 2f) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Cap fling velocity. A very fast fling demands new raster tiles faster than the WebView
     * renderer's shared tile-memory budget can satisfy across the stacked chapter WebViews.
     */
    private val maxFlingVelocity =
        (android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity * 0.60f).toInt()

    override fun fling(velocityY: Int) {
        super.fling(velocityY.coerceIn(-maxFlingVelocity, maxFlingVelocity))
    }

    /**
     * Initialize the view at [initialHref] + [initialProgression]. Call once after attaching to
     * the window.
     */
    fun initialize(
        chapters: List<ChapterEntry>,
        prefs: FormattingPreferences,
        initialHref: String,
        initialProgression: Float,
        publication: Publication,
        alignToTop: Boolean = true,
        focusAnnotationId: String? = null,
    ) {
        controller.initialize(
            chapters = chapters,
            prefs = prefs,
            initialHref = initialHref,
            initialProgression = initialProgression,
            publication = publication,
            alignToTop = alignToTop,
            focusAnnotationId = focusAnnotationId,
        )
        isInitialized.value = true
    }

    override fun updatePreferences(prefs: FormattingPreferences) = controller.updatePreferences(prefs)

    override fun navigateTo(href: String, progression: Float, alignToTop: Boolean) =
        controller.navigateTo(href, progression, alignToTop)

    override fun navigateTo(href: String, progression: Float, alignToTop: Boolean, focusAnnotationId: String?) =
        controller.navigateTo(href, progression, alignToTop, focusAnnotationId)

    /**
     * Resolve the parent-viewport Y (in scrollY-space) of anchor [fragmentId] within [chapterHref].
     * See [ContinuousWindowController.anchorAbsoluteY]. Used to make an in-view same-document
     * cross-reference tap a no-op.
     */
    internal fun anchorAbsoluteY(chapterHref: String, fragmentId: String, callback: (Int?) -> Unit) =
        controller.anchorAbsoluteY(chapterHref, fragmentId, callback)

    /**
     * Resolve Cadence's start-span id inside [chapterHref] using the reader's current viewport
     * projected into that chapter's DOM. See [ContinuousWindowController.cadenceStartSpanId] for
     * the section-heading logic; the reader screen calls this instead of
     * [com.riffle.app.feature.reader.renderer.RendererBridge.cadenceStartSpanId] when running in
     * continuous mode, because the Readium fragment is parked at height=0 and holds no DOM.
     */
    internal fun cadenceStartSpanId(chapterHref: String, callback: (String?) -> Unit) =
        controller.cadenceStartSpanId(chapterHref, callback)

    /**
     * Install a per-chapter Cadence hook — delegates to
     * [ContinuousWindowController.setCadenceOnChapterLoaded]. The reader screen calls this once
     * the Cadence session is bound so every loaded chapter triggers a DOM tokenisation via
     * [com.riffle.app.feature.reader.cadence.CadenceDomScript.tokeniseChapterJs]. Null clears.
     */
    fun setCadenceOnChapterLoaded(hook: ((wv: ChapterWebViewLike) -> Unit)?) {
        controller.setCadenceOnChapterLoaded(hook)
    }


    override fun scrollByPage(forward: Boolean) = controller.scrollByPage(forward)

    override fun applyHighlightDomPatch(
        patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch,
    ) = controller.applyHighlightDomPatch(patch)

    override fun highlightInChapter(href: String, fragmentId: String?, text: String, cssColor: String) =
        controller.highlightInChapter(href, fragmentId, text, cssColor)

    override fun clearHighlightInChapter(href: String) = controller.clearHighlightInChapter(href)

    override fun applySearchHighlights(state: SearchHighlightsState?) =
        controller.applySearchHighlights(state)

    override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) =
        controller.applyAnnotationHighlights(annotationsByHref)

    /**
     * Push the current figure-border rules to every loaded chapter WebView. Mirrors the
     * paginated path in [com.riffle.app.feature.reader.renderer.DefaultRendererBridge.applyFigureBorders]
     * — same CSS + SVG-matching JS, applied per chapter. State is remembered so chapters entering
     * the sliding window later (via [ContinuousDecorationController.onChapterLoaded]) re-apply.
     */
    fun applyFigureBorders(
        cssRules: List<String>,
        svgMatches: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.SvgMatch>,
        rasterMarks: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.RasterMark> = emptyList(),
    ) = controller.applyFigureBorders(cssRules, svgMatches, rasterMarks)

    /**
     * Abort any in-progress fling animation so that the OverScroller stops updating scrollY.
     *
     * [NestedScrollView] does not expose a public abort-fling API, so we reach the mScroller field
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
