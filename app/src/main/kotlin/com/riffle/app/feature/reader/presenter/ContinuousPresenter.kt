package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.ContinuousNavigationView
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * The continuous-mode adapter for [ReaderPresenter]. Wraps a [ContinuousReaderView] (custom
 * endless-scroll renderer used in [com.riffle.core.domain.ReaderOrientation.Continuous] mode)
 * so the view-model never imports continuous-rendering types.
 *
 * Issue #300 cuts over: volume-key paging ([pageBy]), and position events
 * ([positionEvents] — fed from [ContinuousReaderCoordinator]'s `view.onRawPosition` handler).
 * Decoration application via [ContinuousHighlightRenderer] still goes through the existing
 * `targetProvider` lambda; that path collapses when the view-model owns decoration
 * orchestration (out of this issue's scope).
 *
 * Lifecycle parallels [ReadiumPresenter]: construct once per reader session in continuous mode,
 * call [attach] when the view is created, [detach] when the view goes away.
 */
internal class ContinuousPresenter : ReaderPresenter {

    private val _positionEvents = MutableSharedFlow<PositionUpdate>(replay = 0, extraBufferCapacity = 64)
    private val _pageLoadEvents = MutableSharedFlow<PageLoadGeneration>(replay = 0, extraBufferCapacity = 64)
    private val _tapEvents = MutableSharedFlow<TapEvent>(replay = 0, extraBufferCapacity = 64)
    private val _linkEvents = MutableSharedFlow<LinkEvent>(replay = 0, extraBufferCapacity = 64)
    private val _selectionEvents = MutableSharedFlow<SelectionEvent>(replay = 0, extraBufferCapacity = 64)
    private val _annotationTapEvents = MutableSharedFlow<AnnotationTapEvent>(replay = 0, extraBufferCapacity = 64)

    override val positionEvents: SharedFlow<PositionUpdate> = _positionEvents
    override val pageLoadEvents: SharedFlow<PageLoadGeneration> = _pageLoadEvents
    override val tapEvents: SharedFlow<TapEvent> = _tapEvents
    override val linkEvents: SharedFlow<LinkEvent> = _linkEvents
    override val selectionEvents: SharedFlow<SelectionEvent> = _selectionEvents
    override val annotationTapEvents: SharedFlow<AnnotationTapEvent> = _annotationTapEvents

    @Volatile private var view: ContinuousNavigationView? = null
    private var lastPosition: ReaderPosition? = null

    /** Bind a freshly-created continuous view. Idempotent. */
    fun attach(view: ContinuousNavigationView) {
        this.view = view
    }

    /** Release the view (e.g. on disposal). Safe to call repeatedly. */
    fun detach() {
        this.view = null
    }

    // ----- Feed methods called by the coordinator and (future) the screen's callbacks --------
    //
    // [feedPosition] is wired today via [ContinuousReaderCoordinator]. The remaining feeders
    // exist so future orchestrators can subscribe to [tapEvents] / [linkEvents] / etc. without
    // touching ContinuousReaderView callbacks directly.

    fun feedPosition(href: String, progression: Float, totalProgression: Float?, locatorJson: String) {
        val position = ReaderPosition(href, progression, totalProgression, locatorJson)
        lastPosition = position
        _positionEvents.tryEmit(PositionUpdate(position, 0L))
    }

    fun feedTap() {
        _tapEvents.tryEmit(TapEvent.Body)
    }

    fun feedInternalLink(href: String, originLocatorJson: String) {
        _linkEvents.tryEmit(LinkEvent.InternalLink(href, originLocatorJson))
    }

    fun feedExternalLink(url: String) {
        _linkEvents.tryEmit(LinkEvent.ExternalLink(url))
    }

    fun feedFootnote(contentHtml: String) {
        _linkEvents.tryEmit(LinkEvent.Footnote(contentHtml))
    }

    fun feedHighlightSelection(href: String, text: String, progression: Float, before: String, after: String) {
        _selectionEvents.tryEmit(SelectionEvent.HighlightRequest(href, text, progression, before, after))
    }

    fun feedAnnotationHighlightTap(href: String, annotationId: String) {
        _annotationTapEvents.tryEmit(AnnotationTapEvent.Highlight(href, annotationId))
    }

    fun feedAnnotationNoteGlyphTap(href: String, annotationId: String) {
        _annotationTapEvents.tryEmit(AnnotationTapEvent.NoteGlyph(href, annotationId))
    }

    // ----- ReaderPresenter commands ---------------------------------------------------------

    override suspend fun navigateTo(target: NavigationTarget, options: NavigationOptions) {
        val view = view ?: return
        when (target) {
            is NavigationTarget.ToHref -> view.navigateTo(href = target.href, progression = 0f, alignToTop = true)
            is NavigationTarget.ToProgression -> view.navigateTo(
                href = target.href,
                progression = target.progression,
                alignToTop = options.alignToTop,
            )
            is NavigationTarget.ToLocatorJson -> {
                // Reader-side Locator JSON is `{href, locations: {progression, fragments[]}}` —
                // ContinuousReaderView.navigateTo wants (href[#anchor], progression, alignToTop),
                // so parse those three fields directly. Going through Readium's Locator.fromJSON
                // here would pull android.net.Uri into a unit-testable path for no payoff.
                val parsed = runCatching { JSONObject(target.locatorJson) }.getOrNull() ?: return
                val href = parsed.optString("href").takeIf { it.isNotEmpty() } ?: return
                val locations = parsed.optJSONObject("locations")
                val progression = locations?.optDouble("progression", 0.0)?.toFloat() ?: 0f
                val anchor = locations?.optJSONArray("fragments")
                    ?.takeIf { it.length() > 0 }
                    ?.optString(0)
                    ?.takeIf { it.isNotEmpty() }
                val fullHref = if (anchor != null) "$href#$anchor" else href
                view.navigateTo(fullHref, progression, alignToTop = options.alignToTop)
            }
        }
    }

    override suspend fun applyTypography(prefs: FormattingPreferences) {
        view?.updatePreferences(prefs)
    }

    override fun snapshotPosition(): ReaderPosition? = lastPosition

    override suspend fun pageBy(direction: PageDirection) {
        val view = view ?: return
        view.scrollByPage(forward = direction == PageDirection.Forward)
    }

    // Continuous mode runs its readaloud highlight through ContinuousReaderView's own JS injection
    // pipeline; the paginated column-snap protocol does not apply, so these methods always report
    // Unavailable and the screen's snap effects short-circuit accordingly.
    override suspend fun followReadaloudSentence(text: String): ReadaloudFollowResult =
        ReadaloudFollowResult.Unavailable

    override suspend fun measureReadaloudColumns(text: String): List<Double> = emptyList()

    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) = Unit

    // Continuous mode's chapter boundaries are tracked internally by ContinuousReaderView (window
    // shifting, infinite-scroll math), so the screen's ScrollBoundaryNavigationContainer is bypassed
    // entirely in this mode. The seam returns None so the (vertical-only) boundary-poll loop in
    // EpubReaderScreen is harmless if it ever runs in continuous mode.
    override suspend fun scrollBoundary(): ScrollBoundary = ScrollBoundary.None
}
