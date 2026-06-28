package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.ContinuousReaderView
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The continuous-mode adapter for [ReaderPresenter]. Wraps a [ContinuousReaderView] (custom
 * endless-scroll renderer used in [com.riffle.core.domain.ReaderOrientation.Continuous] mode)
 * so the view-model never imports continuous-rendering types.
 *
 * **Step 5 scope (issue #300).** The class exists, owns its event flows, and exposes
 * [attach]/[detach] + the basic command set ([applyTypography], [pageBy], [navigateTo]).
 * Position events still flow through the existing screen-level `onPositionChanged` lambda;
 * step 6 routes that through [positionEvents].
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

    @Volatile private var view: ContinuousReaderView? = null
    private var lastPosition: ReaderPosition? = null

    /** Bind a freshly-created [ContinuousReaderView]. Idempotent. */
    fun attach(view: ContinuousReaderView) {
        this.view = view
    }

    /** Release the view (e.g. on disposal). Safe to call repeatedly. */
    fun detach() {
        this.view = null
    }

    // ----- Feed methods called by the screen's existing continuous-view callback wiring ------
    //
    // The view exposes nine plain-Kotlin callbacks (onRawPosition, onTap, onInternalLinkTapped,
    // etc.). Step 6 will route them through these feeders so [positionEvents], [tapEvents], and
    // friends become the canonical paths. Until then, dormant.

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

    override suspend fun navigateTo(target: NavigationTarget) {
        val view = view ?: return
        when (target) {
            is NavigationTarget.ToHref -> view.navigateTo(href = target.href, progression = 0f, alignToTop = true)
            is NavigationTarget.ToProgression -> view.navigateTo(
                href = target.href,
                progression = target.progression,
                alignToTop = false,
            )
            is NavigationTarget.ToLocatorJson -> {
                // Continuous-mode resume reads (href, progression) out of the Locator JSON. The
                // existing ContinuousReaderCoordinator does the parse; until step 6 lets it call
                // back through here, decline to handle Locator-JSON navigation in the presenter
                // and let the coordinator continue to drive resume.
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
}
