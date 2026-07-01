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
)

internal class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs), ContinuousHighlightTarget, ContinuousNavigationView {

    data class ChapterEntry(val link: Link, val url: String)

    private val port = object : ContinuousScrollPort {
        override val viewportHeightPx: Int get() = height
        override val currentScrollY: Int get() = scrollY
        override fun scrollTo(y: Int) = this@ContinuousReaderView.scrollTo(0, y)
        override fun scrollBy(dy: Int) = this@ContinuousReaderView.scrollBy(0, dy)
        override fun smoothScrollTo(y: Int) = this@ContinuousReaderView.smoothScrollTo(0, y)
        override fun smoothScrollBy(dy: Int) = this@ContinuousReaderView.smoothScrollBy(0, dy)
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

    /** Set by [install]; invoked by the controller with the raw `(href, progression)` on
     *  every scroll-position update. */
    private var onRawPosition: ((href: String, progression: Float) -> Unit)? = null

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
        onRawPosition: (href: String, progression: Float) -> Unit,
    ) {
        this.onRawPosition = onRawPosition
        val binder = ChapterWebViewBinder(
            navigation = navigation,
            links = links,
            annotations = annotations,
            screenRectOf = ::screenRectFor,
            onRenderGone = { controller.onRendererGone() },
            onInternalLink = onInternalLink,
            onSelectionActiveChanged = ::onChildSelectionActiveChanged,
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

    private fun onChildSelectionActiveChanged(active: Boolean) {
        if (active) selectionActiveCount++
        else if (selectionActiveCount > 0) selectionActiveCount--
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

    override fun scrollByPage(forward: Boolean) = controller.scrollByPage(forward)

    override fun highlightInChapter(href: String, text: String, cssColor: String) =
        controller.highlightInChapter(href, text, cssColor)

    override fun clearHighlightInChapter(href: String) = controller.clearHighlightInChapter(href)

    override fun applySearchHighlights(state: SearchHighlightsState?) =
        controller.applySearchHighlights(state)

    override fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>) =
        controller.applyAnnotationHighlights(annotationsByHref)

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
