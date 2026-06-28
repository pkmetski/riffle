package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.ColumnSnap
import com.riffle.app.feature.reader.toEpubPreferences
import com.riffle.app.feature.reader.typographyOverrideInjectionJs
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * The Readium adapter for [ReaderPresenter]. Wraps an [EpubNavigatorFragment] (paginated +
 * vertical modes) so the view-model never imports Readium types.
 *
 * Lifecycle: construct once per reader session (the screen does so when not in continuous mode),
 * call [attach] when the fragment is available, [detach] when it goes away (e.g. orientation
 * change). All events fired through the feed methods are buffered (extra capacity 64) so
 * producers never suspend.
 *
 * Issue #300 cuts over: decoration application ([applyDecorations]), navigation
 * ([navigateToLocator], [navigateToLink], [pageBy]), typography ([applyTypography] +
 * [updateLayoutContext]). Fragment-listener events (`feedPageLoaded`,
 * `feedInternalLink`, …) exist for future orchestrators; the screen still installs the
 * Readium listeners directly today.
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

    /**
     * Layout state the screen owns (device rotation, EPUB layout type). The Readium
     * [submitPreferences] call needs both to derive RS column count and the fixed-layout flag;
     * the screen pushes them in via [updateLayoutContext] whenever they change.
     */
    @Volatile private var isLandscape: Boolean = false
    @Volatile private var isFixedLayout: Boolean = false

    /** Push the current device/EPUB layout into the presenter before [applyTypography] runs. */
    fun updateLayoutContext(isLandscape: Boolean, isFixedLayout: Boolean) {
        this.isLandscape = isLandscape
        this.isFixedLayout = isFixedLayout
    }

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
    // [PaginationListener.onPageLoaded], [EpubNavigatorFragment.Listener.shouldFollowInternalLink],
    // tap zones, selection, and annotation-tap callbacks forward to these so [pageLoadEvents],
    // [linkEvents], [tapEvents], [selectionEvents], and [annotationTapEvents] become the
    // canonical paths for future orchestrators. The screen still installs the listeners and
    // wires them up; the view-model split is what removes that intermediate.

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
        // The Readium-typed convenience overloads ([navigateToLocator], [navigateToLink]) own
        // the column-snap dance. This abstract entry point — used by future orchestrators that
        // only know the abstract [NavigationTarget] shape — issues a plain go(); the snap
        // backstop on PaginationListener.onPageLoaded rounds the page if needed.
        fragment.go(locator, animated = true)
    }

    override suspend fun applyTypography(prefs: FormattingPreferences) {
        val fragment = fragment ?: return
        // Live-update Readium's typography for the attached fragment. The screen previously
        // called `fragmentRef.value?.submitPreferences(...)` directly; routing through here keeps
        // the layout context (orientation + fixed-layout flag) on the presenter rather than
        // forcing every caller to thread it through.
        fragment.submitPreferences(prefs.toEpubPreferences(isLandscape, isFixedLayout))
        // The injected CSS overrides (font family, custom margins) are also re-applied on each
        // onPageLoaded by PaginationListener; doing it here too means a live preferences change
        // takes effect immediately without waiting for the next page turn.
        fragment.evaluateJavascript(typographyOverrideInjectionJs())
    }

    override fun snapshotPosition(): ReaderPosition? = lastPosition

    /**
     * Apply Readium decorations to the currently attached fragment for a given group. No-op when
     * no fragment is attached or the fragment is not a [DecorableNavigator] — exactly mirrors the
     * original screen-level `applyDecorationsBlock` lambda this method replaces.
     *
     * Called from [com.riffle.app.feature.reader.ReadiumHighlightRenderer] via the screen's
     * `applyDecorationsBlock` lambda.
     */
    suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        val nav = fragment as? DecorableNavigator ?: return
        withContext(Dispatchers.Main) { nav.applyDecorations(decorations, group) }
    }

    /**
     * Stable token that changes whenever the attached fragment changes. Replaces the
     * `currentNavigatorStamp` lambda the screen passes into the highlight renderer: search-result
     * settle loops break out when the stamp changes mid-iteration.
     */
    fun attachmentStamp(): Any? = fragment

    // ----- Readium-typed navigation ---------------------------------------------------------
    //
    // The screen still constructs Readium Locator/Link from TOC entries, search results, and
    // server progress; these convenience overloads keep the type-conversion at the boundary
    // instead of forcing every caller to round-trip through JSON. They collapse into
    // [navigateTo] once the view-model owns navigation entirely (out of this issue's scope).

    /**
     * Navigate to [locator] and snap to the column it landed in. Replaces the screen-level
     * `ColumnSnap.goAndSnap` and `fragment.go` calls. In vertical (scroll) mode the snap JS is a
     * no-op, so passing `snap = true` is safe for both orientations; pass `snap = false` to skip
     * the snap entirely when the caller knows the page must not be rounded (e.g. the readaloud
     * `play-from-here` resume which already lands precisely).
     */
    suspend fun navigateToLocator(
        locator: Locator,
        landAtStartWhenNoTarget: Boolean = true,
        snap: Boolean = true,
        animated: Boolean = true,
    ) {
        val fragment = fragment ?: return
        if (snap) {
            ColumnSnap.goAndSnap(fragment, locator, landAtStartWhenNoTarget)
        } else {
            fragment.go(locator, animated = animated)
        }
    }

    /**
     * Navigate to [link] (TOC tap, internal cross-resource link) and snap to the column it
     * landed in. Replaces the screen-level `ColumnSnap.goAndSnap(fragment, link)`.
     */
    suspend fun navigateToLink(link: Link) {
        val fragment = fragment ?: return
        ColumnSnap.goAndSnap(fragment, link)
    }

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
