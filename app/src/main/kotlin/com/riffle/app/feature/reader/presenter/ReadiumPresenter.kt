package com.riffle.app.feature.reader.presenter

import com.riffle.app.feature.reader.renderer.RendererBridge
import com.riffle.app.feature.reader.toEpubPreferences
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
 * After #331, every paginated/vertical `evaluateJavascript(` call flows through the supplied
 * [RendererBridge] — navigation snapping, typography overrides, scroll-boundary probes, the
 * readaloud follow primitives. The presenter still owns Readium's typed APIs (`submitPreferences`,
 * `applyDecorations`, `go`, `goForward`/`goBackward`) because those are not JS calls.
 */
internal class ReadiumPresenter(
    private val scope: CoroutineScope,
    private val publication: Publication,
    private val bridge: RendererBridge,
    private val mainDispatcher: CoroutineDispatcher,
) : ReaderPresenter {

    private val _positionEvents = MutableSharedFlow<PositionUpdate>(replay = 0, extraBufferCapacity = 64)
    private val _pageLoadEvents = MutableSharedFlow<PageLoadGeneration>(replay = 0, extraBufferCapacity = 64)
    private val _viewportFractionEvents = MutableSharedFlow<Pair<String, Double>>(replay = 0, extraBufferCapacity = 64)
    private val _tapEvents = MutableSharedFlow<TapEvent>(replay = 0, extraBufferCapacity = 64)
    private val _linkEvents = MutableSharedFlow<LinkEvent>(replay = 0, extraBufferCapacity = 64)
    private val _selectionEvents = MutableSharedFlow<SelectionEvent>(replay = 0, extraBufferCapacity = 64)
    private val _annotationTapEvents = MutableSharedFlow<AnnotationTapEvent>(replay = 0, extraBufferCapacity = 64)

    override val positionEvents: SharedFlow<PositionUpdate> = _positionEvents
    override val pageLoadEvents: SharedFlow<PageLoadGeneration> = _pageLoadEvents
    override val viewportFractionEvents: SharedFlow<Pair<String, Double>> = _viewportFractionEvents
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

    /**
     * Bind a freshly-created fragment. Starts forwarding its `currentLocator` to [positionEvents].
     *
     * Idempotent: calling `attach` again with the same fragment instance returns without churning
     * the position collection. This matters because the screen now has two attach call-sites — the
     * AndroidView factory's one-shot and a `LaunchedEffect(readiumPresenter, fragmentRef.value)`
     * that re-attaches whenever either changes — and on a cold open in paginated mode both fire
     * back-to-back with the same `(presenter, fragment)` pair.
     */
    fun attach(fragment: EpubNavigatorFragment) {
        if (this.fragment === fragment) return
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

    fun feedPageLoaded() {
        pageLoadCount += 1
        _pageLoadEvents.tryEmit(PageLoadGeneration(pageLoadCount))
        publishViewportFraction()
    }

    /**
     * Measure the current document's `viewportSize / scrollSize` and publish it against the
     * fragment's current-locator href. Called on page load and after typography changes (the
     * two hooks Readium re-lays-out the document). Scroll callbacks intentionally do NOT
     * trigger this — see issue #399's flake-avoidance rules.
     */
    private fun publishViewportFraction() {
        val fragment = fragment ?: return
        val href = fragment.currentLocator.value.href.toString()
        if (href.isEmpty()) return
        scope.launch {
            val fraction = bridge.readViewportFraction() ?: return@launch
            if (fraction > 0.0) {
                _viewportFractionEvents.tryEmit(href to fraction)
            }
        }
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

    override suspend fun navigateTo(target: NavigationTarget, options: NavigationOptions) {
        val fragment = fragment ?: return
        val locator = target.toLocator(publication) ?: return
        if (options.snap) {
            bridge.snapAfterGoTo(locator, options.landAtStartWhenNoTarget)
        } else {
            fragment.go(locator, animated = options.animated)
        }
    }

    override suspend fun applyTypography(prefs: FormattingPreferences) {
        val fragment = fragment ?: return
        fragment.submitPreferences(prefs.toEpubPreferences(isLandscape, isFixedLayout))
        // The injected CSS overrides (font family, custom margins) are also re-applied on each
        // onPageLoaded by the bridge's TypographyOverride capability; doing it here too means a
        // live preferences change takes effect immediately without waiting for the next page turn.
        bridge.applyTypographyOverride()
        // Font size / margins reflow the document. Re-measure the viewport fraction so the
        // bookmark eps stays accurate under the new layout (issue #399).
        publishViewportFraction()
    }

    override fun snapshotPosition(): ReaderPosition? = lastPosition

    /**
     * Apply Readium decorations to the currently attached fragment for a given group. No-op when
     * no fragment is attached or the fragment is not a [DecorableNavigator]. Called from
     * [com.riffle.app.feature.reader.ReadiumHighlightRenderer] via the screen's
     * `applyDecorationsBlock` lambda.
     */
    suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        val nav = fragment as? DecorableNavigator ?: return
        withContext(mainDispatcher) { nav.applyDecorations(decorations, group) }
    }

    /** Stable token that changes whenever the attached fragment changes. */
    fun attachmentStamp(): Any? = fragment

    // ----- Readium-typed navigation ---------------------------------------------------------

    /**
     * Navigate to [locator] and (optionally) snap to its column. The snap path goes through the
     * bridge; the no-snap path uses Readium's `go()` directly because the caller knows the page
     * must not be rounded (e.g. the readaloud `play-from-here` resume which already lands precisely).
     */
    suspend fun navigateToLocator(
        locator: Locator,
        landAtStartWhenNoTarget: Boolean = true,
        snap: Boolean = true,
        animated: Boolean = true,
    ) {
        val fragment = fragment ?: return
        if (snap) {
            bridge.snapAfterGoTo(locator, landAtStartWhenNoTarget)
        } else {
            fragment.go(locator, animated = animated)
        }
    }

    /** Navigate to [link] (TOC tap, internal cross-resource link) and snap to its column. */
    suspend fun navigateToLink(link: Link) {
        if (fragment == null) return
        bridge.snapAfterGoTo(link)
    }

    override suspend fun pageBy(direction: PageDirection) {
        val fragment = fragment ?: return
        when (direction) {
            PageDirection.Forward -> fragment.goForward(animated = false)
            PageDirection.Backward -> fragment.goBackward(animated = false)
        }
    }

    override suspend fun followReadaloudSentence(text: String): ReadaloudFollowResult {
        if (fragment == null) return ReadaloudFollowResult.Unavailable
        val where = bridge.followNarratedSentence(text) ?: return ReadaloudFollowResult.Unavailable
        return if (where == "off") ReadaloudFollowResult.OffPage else ReadaloudFollowResult.Snapped
    }

    override suspend fun followCadenceSpan(fragmentId: String): ReadaloudFollowResult {
        if (fragment == null) return ReadaloudFollowResult.Unavailable
        // Three-way outcome: "moved" (snap changed the page), "same" (already on-page),
        // "absent" (id not in this resource → OffPage so the caller navigates its chapter).
        // Collapsing "same" into OffPage would trigger a chapter navigation every tick while
        // the sentence sits comfortably visible.
        return when (bridge.snapCadenceSpan(fragmentId)) {
            "moved", "same" -> ReadaloudFollowResult.Snapped
            "absent" -> ReadaloudFollowResult.OffPage
            else -> ReadaloudFollowResult.Unavailable
        }
    }

    override suspend fun measureReadaloudColumns(text: String): List<Double> {
        if (fragment == null) return emptyList()
        return bridge.measureNarratedColumns(text)
    }

    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {
        if (fragment == null) return
        bridge.snapNarratedColumn(text, columnIndex)
    }

    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> {
        if (fragment == null) return emptyList()
        return bridge.measureCadenceColumns(fragmentId)
    }

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {
        if (fragment == null) return
        bridge.snapCadenceColumn(fragmentId, columnIndex)
    }

    override suspend fun applyHighlightDomPatch(
        patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch,
    ) {
        if (fragment == null) return
        bridge.applyHighlightDomPatch(patch)
    }

    override suspend fun scrollBoundary(): ScrollBoundary {
        if (fragment == null) return ScrollBoundary.None
        val (atForward, atBackward) = bridge.scrollBoundary()
        return ScrollBoundary(atForwardBoundary = atForward, atBackwardBoundary = atBackward)
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
