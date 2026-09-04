package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.presenter.AnnotationTapEvent
import com.riffle.app.feature.reader.presenter.LinkEvent
import com.riffle.app.feature.reader.presenter.NavigationOptions
import com.riffle.app.feature.reader.presenter.NavigationTarget
import com.riffle.app.feature.reader.presenter.PageDirection
import com.riffle.app.feature.reader.presenter.ReaderPresenter
import com.riffle.app.feature.reader.presenter.ReadaloudFollowResult
import com.riffle.app.feature.reader.presenter.ScrollBoundary
import com.riffle.app.feature.reader.presenter.SelectionEvent
import com.riffle.feature.reader.EpubNavigatorInterface
import com.riffle.feature.reader.LocatorJson
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationOptions
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorScrollBoundary
import com.riffle.feature.reader.NavigatorSearchMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File

/**
 * Android implementation of [EpubNavigatorInterface]. Owns book opening via [AssetRetriever]
 * and [PublicationOpener]; bridges [ReaderPresenter] events into the platform-neutral interface.
 *
 * Lifecycle:
 * 1. ViewModel calls [open] → publication stored internally.
 * 2. Screen observes [publicationState] to create [com.riffle.app.feature.reader.presenter.ReadiumPresenter].
 * 3. Screen calls [setPresenter] with the created presenter.
 * 4. Interface calls (navigation, decorations, etc.) delegate to the presenter.
 */
class AndroidEpubNavigator(
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
) : EpubNavigatorInterface {

    /** Emits the opened [Publication] once [open] completes; null before open or after [close]. */
    val publicationState: StateFlow<Publication?> get() = _publicationState
    private val _publicationState = MutableStateFlow<Publication?>(null)

    @Volatile private var presenter: ReaderPresenter? = null

    /** Called by the screen after it constructs a [ReaderPresenter] from [publicationState]. */
    internal fun setPresenter(p: ReaderPresenter) {
        presenter = p
    }

    // ── Book lifecycle ──────────────────────────────────────────────────────────

    override suspend fun open(bookFilePath: String, initialLocatorJson: LocatorJson?) {
        val url = AbsoluteUrl("file://$bookFilePath") ?: error("Cannot build URL for $bookFilePath")
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> error("Cannot retrieve asset at $bookFilePath: ${r.value}")
        }
        val pub = when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> error("Cannot open publication at $bookFilePath: ${r.value}")
        }
        _publicationState.value = pub
    }

    override fun close() {
        presenter = null
        _publicationState.value = null
    }

    // ── Position and layout events ──────────────────────────────────────────────

    override val positionFlow: Flow<NavigatorPosition>
        get() = presenter?.positionEvents?.map { update ->
            NavigatorPosition(
                href = update.position.href,
                progression = update.position.progression,
                totalProgression = update.position.totalProgression,
                locatorJson = update.position.locatorJson,
            )
        } ?: emptyFlow()

    override val pageLoadEvents: Flow<NavigatorPageLoad>
        get() = presenter?.pageLoadEvents?.map { NavigatorPageLoad(it.value) } ?: emptyFlow()

    override val viewportFractionEvents: Flow<Pair<String, Double>>
        get() = presenter?.viewportFractionEvents ?: emptyFlow()

    // ── User interaction events ─────────────────────────────────────────────────

    override val eventFlow: Flow<NavigatorEvent>
        get() {
            val p = presenter ?: return emptyFlow()
            return merge(
                p.tapEvents.map { NavigatorEvent.BodyTap },
                p.linkEvents.map { link ->
                    when (link) {
                        is LinkEvent.InternalLink ->
                            NavigatorEvent.InternalLink(link.href, link.originLocatorJson)
                        is LinkEvent.ExternalLink ->
                            NavigatorEvent.ExternalLink(link.url)
                        is LinkEvent.Footnote ->
                            NavigatorEvent.Footnote(link.contentHtml)
                    }
                },
                p.selectionEvents.map { sel ->
                    when (sel) {
                        is SelectionEvent.HighlightRequest ->
                            NavigatorEvent.HighlightRequest(sel.href, sel.text, sel.progression, sel.before, sel.after)
                        is SelectionEvent.PlayFromHereRequest ->
                            NavigatorEvent.PlayFromHereRequest(sel.href, sel.text, sel.resolverJs)
                    }
                },
                p.annotationTapEvents.map { tap ->
                    when (tap) {
                        is AnnotationTapEvent.Highlight ->
                            NavigatorEvent.AnnotationHighlightTap(tap.href, tap.annotationId)
                        is AnnotationTapEvent.NoteGlyph ->
                            NavigatorEvent.AnnotationGlyphTap(tap.href, tap.annotationId)
                    }
                },
            )
        }

    // ── Navigation commands ─────────────────────────────────────────────────────

    override suspend fun navigateTo(
        target: NavigatorNavigationTarget,
        options: NavigatorNavigationOptions,
    ) {
        val presenterTarget = when (target) {
            is NavigatorNavigationTarget.ToLocatorJson ->
                NavigationTarget.ToLocatorJson(target.locatorJson)
            is NavigatorNavigationTarget.ToHref ->
                NavigationTarget.ToHref(target.href, target.fragment)
            is NavigatorNavigationTarget.ToProgression ->
                NavigationTarget.ToProgression(target.href, target.progression)
        }
        val presenterOptions = NavigationOptions(
            snap = options.snap,
            landAtStartWhenNoTarget = options.landAtStartWhenNoTarget,
            snapProgressionToNearestColumn = options.snapProgressionToNearestColumn,
            animated = options.animated,
            alignToTop = options.alignToTop,
            focusAnnotationId = options.focusAnnotationId,
        )
        presenter?.navigateTo(presenterTarget, presenterOptions)
    }

    override suspend fun pageBy(direction: NavigatorPageDirection) {
        presenter?.pageBy(
            if (direction == NavigatorPageDirection.Forward) PageDirection.Forward
            else PageDirection.Backward,
        )
    }

    // ── Visual commands ─────────────────────────────────────────────────────────

    override fun applyDecorations(group: String, decorations: List<NavigatorDecoration>) {
        // Full Readium Decoration mapping deferred to the decoration bridge refactor.
        // The existing decoration path (applyDecorations via ReadiumPresenter's
        // DecorableNavigator) is unchanged; this no-op does not regress existing behaviour.
    }

    override suspend fun applyHighlightDomPatch(patchJson: String) {
        // Deserialise and route via the existing HighlightsDomPatch bridge in EpubReaderScreen.
        // Full wiring is deferred; the existing patch path is unchanged.
    }

    // ── Readaloud and Cadence ───────────────────────────────────────────────────

    override suspend fun followReadaloudSentence(text: String): NavigatorFollowResult =
        presenter?.followReadaloudSentence(text).toNavigatorFollowResult()

    override suspend fun followCadenceSpan(fragmentId: String): NavigatorFollowResult =
        presenter?.followCadenceSpan(fragmentId).toNavigatorFollowResult()

    override suspend fun measureReadaloudColumns(text: String): List<Double> =
        presenter?.measureReadaloudColumns(text) ?: emptyList()

    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {
        presenter?.snapReadaloudColumn(text, columnIndex)
    }

    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> =
        presenter?.measureCadenceColumns(fragmentId) ?: emptyList()

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {
        presenter?.snapCadenceColumn(fragmentId, columnIndex)
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): Flow<List<NavigatorSearchMatch>> = emptyFlow()

    // ── Misc ────────────────────────────────────────────────────────────────────

    override fun snapshotPosition(): NavigatorPosition? =
        presenter?.snapshotPosition()?.let { pos ->
            NavigatorPosition(pos.href, pos.progression, pos.totalProgression, pos.locatorJson)
        }

    override suspend fun getChapterBytes(href: String): ByteArray? {
        val pub = _publicationState.value ?: return null
        return runCatching {
            val url = org.readium.r2.shared.util.Url(href) ?: return@runCatching null
            pub.get(url)?.read()?.getOrNull()
        }.getOrNull()
    }

    override suspend fun scrollBoundary(): NavigatorScrollBoundary {
        val b = presenter?.scrollBoundary() ?: return NavigatorScrollBoundary.None
        return NavigatorScrollBoundary(b.atForwardBoundary, b.atBackwardBoundary)
    }

    private fun ReadaloudFollowResult?.toNavigatorFollowResult(): NavigatorFollowResult =
        when (this) {
            ReadaloudFollowResult.Snapped -> NavigatorFollowResult.Snapped
            ReadaloudFollowResult.OffPage -> NavigatorFollowResult.OffPage
            null, ReadaloudFollowResult.Unavailable -> NavigatorFollowResult.Unavailable
        }
}
