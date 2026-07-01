package com.riffle.app.feature.reader

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntRect
import com.riffle.app.feature.reader.presenter.ContinuousPresenter
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Owns all continuous-mode wiring inside [EpubReaderScreen]: callback setup on the
 * [ContinuousReaderView], navigation routing, volume key handling, and preference forwarding.
 *
 * Create once with [remember] in the screen. The screen calls [attach] from the [AndroidView]
 * factory as soon as the view is created. Navigation methods are suspending and are called from
 * the screen's LaunchedEffects.
 *
 * The [ContinuousNavigationSink], [ContinuousLinkSink], and [ContinuousAnnotationSink]
 * implementations are passed at construction time. They close over the screen's
 * [rememberUpdatedState] delegates, so each invocation reads the latest value rather than the
 * value captured at [remember] time.
 */
internal class ContinuousReaderCoordinator(
    private val publication: Publication,
    private val spinePositionsProvider: () -> Pair<List<String>, List<Int>>,
    private val latestLocator: () -> Locator?,
    private val sentenceQuotesProvider: () -> Map<String, SentenceQuote>,
    private val sentenceChaptersProvider: () -> Map<String, String>,
    private val navigation: ContinuousNavigationSink,
    private val links: ContinuousLinkSink,
    private val annotations: ContinuousAnnotationSink,
) {
    private var view: ContinuousReaderView? = null

    /**
     * Optional [ContinuousPresenter] hook for the seam (issue #300 step 6). When non-null,
     * continuous-mode raw-position events also feed [ContinuousPresenter.feedPosition] so
     * [ContinuousPresenter.positionEvents] becomes the canonical event source for any orchestrator
     * built on top of [com.riffle.app.feature.reader.presenter.ReaderPresenter]. The existing
     * [onLocator] callback path is unchanged in this step — full bypass deletion is the step 7
     * cleanup pass.
     */
    var presenter: ContinuousPresenter? = null

    // Updated in attach() every time a new ContinuousReaderView is created (including after
    // Activity rotation, when Compose recreates the AndroidView and calls attach() again).
    // onTocNavigation awaits the first non-null emission to survive the race between the
    // AndroidView factory (layout phase) and the navigation LaunchedEffect (composition phase),
    // which have no guaranteed ordering on first composition. Using MutableStateFlow rather than
    // CompletableDeferred means the reference is refreshed on rotation — a CompletableDeferred
    // can only be completed once and would return the old destroyed view on subsequent attach().
    private val viewFlow = MutableStateFlow<ContinuousReaderView?>(null)

    /**
     * Wire all [ContinuousReaderView] callbacks. Call from the [AndroidView] factory immediately
     * after the view is created.
     */
    fun attach(view: ContinuousReaderView) {
        this.view = view
        viewFlow.value = view

        view.onRawPosition = { href, progression ->
            val (spineHrefs, counts) = spinePositionsProvider()
            val locator = buildContinuousLocator(href, progression, spineHrefs, counts)
            if (locator != null) {
                navigation.onLocator(locator)
                presenter?.feedPosition(
                    href = locator.href.toString(),
                    progression = locator.locations.progression?.toFloat() ?: progression,
                    totalProgression = locator.locations.totalProgression?.toFloat(),
                    locatorJson = locator.toJSON().toString(),
                )
            }
        }

        view.onTap = { navigation.onTap() }

        view.onInternalLinkTapped = { href ->
            val origin = latestLocator()
            val path = href.substringBefore('#')
            val link = publication.readingOrder.firstOrNull { it.href.toString() == path }
            if (link != null && origin != null) {
                links.onFollowInternalLink(link, origin)
            } else {
                this.view?.navigateTo(href, 0f)
            }
        }

        view.onExternalLinkTapped = { url -> links.onExternalLink(url) }

        view.onFootnoteContent = { content -> links.onFootnote(content) }

        view.onAnnotationTap = { _, id, androidRect ->
            annotations.onAnnotationTap(id, IntRect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom))
        }

        view.onAnnotationNoteTap = { _, id, androidRect ->
            annotations.onAnnotationNoteTap(id, IntRect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom))
        }

        view.onHighlightSelection = { chapterHref, selectedText, progression, selectionScreenRect, before, after ->
            val locator = Locator.fromJSON(
                JSONObject()
                    .put("href", chapterHref)
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", progression))
                    .put("text", JSONObject()
                        .put("before", before)
                        .put("highlight", selectedText)
                        .put("after", after)),
            )
            if (locator != null) {
                annotations.onHighlight(
                    locator,
                    IntRect(selectionScreenRect.left, selectionScreenRect.top, selectionScreenRect.right, selectionScreenRect.bottom),
                )
            }
        }

        view.onPlayFromHereSelection = { chapterHref, selectedText, evalJs ->
            val scoped = scopeSentencesToChapter(
                sentenceQuotesProvider(), sentenceChaptersProvider(), chapterHref,
            )
            evalJs(resolveSelectionSentenceJs(scoped)) { raw ->
                val geomId = raw?.trim('"')?.takeIf { it.isNotEmpty() }
                val sid = geomId
                    ?: ContinuousPositionTracker.sentenceIdForSelection(selectedText, scoped.toMap())
                if (sid != null) annotations.onPlayFromHere("$chapterHref#$sid")
            }
        }
    }

    /**
     * Navigate to a TOC entry or chapter-map segment. Suspends until the view is initialized.
     *
     * Call from the TOC/chapter-map navigation LaunchedEffect when in continuous mode.
     */
    suspend fun onTocNavigation(link: Link) {
        val v = viewFlow.filterNotNull().first()
        snapshotFlow { v.isInitialized.value }.filter { it }.first()
        v.navigateTo(link.href.toString(), 0f)
    }

    /** Forward a volume-key page scroll to the view. */
    fun onVolumeKey(forward: Boolean) {
        view?.scrollByPage(forward)
    }

    /** Push updated formatting preferences to all live chapter WebViews. */
    fun onPreferencesChanged(prefs: FormattingPreferences) {
        view?.updatePreferences(prefs)
    }
}
