package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.typographyOverrideInjectionJs
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * The Readium adapter for [ReaderPresenter]. Wraps an [EpubNavigatorFragment] (paginated +
 * vertical modes) so the view-model never imports Readium types.
 *
 * **Step 1 scope (issue #300).** This adapter exists; no caller is cut over yet. The screen
 * still mounts the fragment and installs Readium listeners directly. Subsequent cutover steps
 * will replace those direct touches with [attach] + the feed methods on this class so the
 * fragment's events flow through the seam.
 *
 * Lifecycle: construct the adapter once per reader session; call [attach] when the fragment is
 * available, [detach] when it goes away (e.g. on orientation change). All events fired through
 * the feed methods are buffered (extra capacity 64) so producers never suspend.
 *
 * @param scope a lifecycle-scoped [CoroutineScope] (usually `viewModelScope`) that owns the
 *   fragment-position collector. It must outlive the adapter.
 * @param publication the open publication, used to parse Locator JSON for [NavigationTarget]s
 *   that arrive as raw JSON (resume from storage).
 */
internal class ReadiumPresenter(
    private val scope: CoroutineScope,
    private val publication: Publication,
) : ReaderPresenter {

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

    @Volatile private var fragment: EpubNavigatorFragment? = null
    private var positionJob: Job? = null
    private var lastPosition: ReaderPosition? = null
    private var positionGeneration: Long = 0L
    private var pageLoadCount: Int = 0

    /** Bind a freshly-created fragment. Starts forwarding its `currentLocator` to [positionEvents]. */
    fun attach(fragment: EpubNavigatorFragment) {
        detach()
        this.fragment = fragment
        positionJob = scope.launch {
            fragment.currentLocator.collect { locator ->
                publishPosition(locator.toReaderPosition())
            }
        }
    }

    /** Release the fragment (e.g. on orientation change). Safe to call repeatedly. */
    fun detach() {
        positionJob?.cancel()
        positionJob = null
        fragment = null
    }

    // ----- Feed methods called by the screen's existing Readium listener objects --------------
    //
    // These exist so Step 3 (route navigation through the adapter) and Step 5 (page-load events)
    // can be small mechanical changes: the listener objects forward to these instead of calling
    // the VM directly. Until then, callers don't exist — these are dormant.

    fun feedPageLoaded() {
        pageLoadCount += 1
        _pageLoadEvents.tryEmit(PageLoadGeneration(pageLoadCount))
    }

    fun feedInternalLink(link: Link, origin: Locator) {
        _linkEvents.tryEmit(
            LinkEvent.InternalLink(
                href = link.href.toString(),
                originLocatorJson = origin.toJSON().toString(),
            ),
        )
    }

    fun feedExternalLink(url: String) {
        _linkEvents.tryEmit(LinkEvent.ExternalLink(url))
    }

    fun feedFootnote(contentHtml: String) {
        _linkEvents.tryEmit(LinkEvent.Footnote(contentHtml))
    }

    fun feedTap() {
        _tapEvents.tryEmit(TapEvent.Body)
    }

    fun feedHighlightSelection(href: String, text: String, progression: Float, before: String?, after: String?) {
        _selectionEvents.tryEmit(SelectionEvent.HighlightRequest(href, text, progression, before, after))
    }

    fun feedPlayFromHereSelection(href: String, text: String, resolverJs: String? = null) {
        _selectionEvents.tryEmit(SelectionEvent.PlayFromHereRequest(href, text, resolverJs))
    }

    fun feedAnnotationHighlightTap(href: String, annotationId: String) {
        _annotationTapEvents.tryEmit(AnnotationTapEvent.Highlight(href, annotationId))
    }

    fun feedAnnotationNoteGlyphTap(href: String, annotationId: String) {
        _annotationTapEvents.tryEmit(AnnotationTapEvent.NoteGlyph(href, annotationId))
    }

    // ----- ReaderPresenter commands ---------------------------------------------------------

    override suspend fun navigateTo(target: NavigationTarget) {
        val fragment = fragment ?: return
        val locator = target.toLocator(publication) ?: return
        // Step 1: plain go(). The column-snap dance currently lives in EpubReaderScreen and moves
        // behind this method at cutover Step 3 (paginated mode → ColumnSnap.goAndSnap, vertical →
        // plain go).
        fragment.go(locator, animated = true)
    }

    override suspend fun applyTypography(prefs: FormattingPreferences) {
        // The adapter doesn't yet own preference→JS translation; it forwards to the existing
        // injection helper. Cutover Step 4 will move the full typography pipeline behind here.
        val fragment = fragment ?: return
        fragment.evaluateJavascript(typographyOverrideInjectionJs())
    }

    override fun snapshotPosition(): ReaderPosition? = lastPosition

    override suspend fun pageBy(direction: PageDirection) {
        val fragment = fragment ?: return
        when (direction) {
            PageDirection.Forward -> fragment.goForward(animated = false)
            PageDirection.Backward -> fragment.goBackward(animated = false)
        }
    }

    // ----- internals ------------------------------------------------------------------------

    private fun publishPosition(position: ReaderPosition) {
        lastPosition = position
        positionGeneration += 1
        _positionEvents.tryEmit(PositionUpdate(position, positionGeneration))
    }
}

// ===== Locator <-> ReaderPosition / NavigationTarget translation ============================
// Package-private so the unit tests can pin the contract without a real fragment.

internal fun Locator.toReaderPosition(): ReaderPosition =
    ReaderPosition(
        href = href.toString(),
        progression = (locations.progression ?: 0.0).toFloat(),
        totalProgression = locations.totalProgression?.toFloat(),
        locatorJson = toJSON().toString(),
    )

internal fun NavigationTarget.toLocator(publication: Publication): Locator? = when (this) {
    is NavigationTarget.ToLocatorJson -> runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    is NavigationTarget.ToHref -> {
        val url = Url(href + (fragment?.let { "#$it" } ?: ""))
        url?.let { publication.linkWithHref(it)?.let { link -> publication.locatorFromLink(link) } }
    }
    is NavigationTarget.ToProgression -> {
        val url = Url(href)
        url?.let { publication.linkWithHref(it) }?.let { link ->
            publication.locatorFromLink(link)?.copyWithProgression(progression)
        }
    }
}

private fun Locator.copyWithProgression(progression: Float): Locator =
    copy(locations = locations.copy(progression = progression.toDouble()))
