package com.riffle.app.feature.reader

import androidx.compose.runtime.snapshotFlow
import com.riffle.app.feature.reader.presenter.ContinuousPresenter
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val coroutineScope: CoroutineScope,
    private val ensureSentenceQuotesReady: suspend () -> Unit,
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
     * [ContinuousNavigationSink.onLocator] path is unchanged in this step — full bypass
     * deletion is the step 7 cleanup pass.
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

        view.install(
            navigation = navigation,
            links = links,
            annotations = annotations,
            onInternalLink = { href ->
                val origin = latestLocator()
                val path = href.substringBefore('#')
                val link = publication.readingOrder.firstOrNull { it.href.toString() == path }
                if (link != null && origin != null) {
                    links.onFollowInternalLink(link, origin)
                } else {
                    this.view?.navigateTo(href, 0f)
                }
            },
            // Same-document (`#id`) figure / cross-reference tap in the child WebView. The paged
            // path (FootnoteAnchorBridge → snapToElement) doesn't apply here — Continuous mode
            // has no Readium fragment to snap against, so we drive the outer viewport ourselves
            // via view.navigateTo, which finds the target's device-Y through anchorOffsetTopDevicePx
            // and scrolls the NestedScrollView to it. Capture the pre-jump origin so the return
            // card can undo the jump, matching the paged-mode behaviour.
            onCrossReference = { chapterHref, fragmentId ->
                val origin = latestLocator()
                if (origin != null) links.captureReturnAnchor(origin)
                this.view?.navigateTo("$chapterHref#$fragmentId", 0f, alignToTop = false)
            },
            onRawPosition = { href, progression ->
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
            },
            onViewportFractionMeasured = { href, fraction ->
                presenter?.feedViewportFraction(href, fraction)
            },
        )

        view.onPlayFromHereSelection = { chapterHref, selectedText, evalJs ->
            coroutineScope.launch {
                handleContinuousPlayFromHere(
                    chapterHref = chapterHref,
                    selectedText = selectedText,
                    evalJs = evalJs,
                    ensureSentenceQuotesReady = ensureSentenceQuotesReady,
                    sentenceQuotesProvider = sentenceQuotesProvider,
                    sentenceChaptersProvider = sentenceChaptersProvider,
                    onPlayFromHere = { ref -> annotations.onPlayFromHere(ref) },
                )
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

/**
 * Pure body of Continuous mode's Play-from-here selection callback. Extracted from
 * [ContinuousReaderCoordinator.attach] so the two silent-drop paths this fix closes
 * (see fix b660397) are exercisable at the JVM unit level.
 *
 * 1. Await [ensureSentenceQuotesReady] BEFORE reading [sentenceQuotesProvider]. The
 *    sentence-quote map is built off-thread from the SMIL sidecar/bundle; the first
 *    Play-from-here tap can race the build and see an empty map, in which case both
 *    [resolveSelectionSentenceJs] and [ContinuousPositionTracker.sentenceIdForSelection]
 *    return null — without the await, the tap vanishes silently.
 * 2. When sid resolution genuinely fails, fall back to the bare [chapterHref] so the
 *    player resolves the nearest narrated clip at/after chapter start (mirrors the
 *    paginated fallback in `EpubReaderScreen.playFromHereActionMode`).
 */
internal suspend fun handleContinuousPlayFromHere(
    chapterHref: String,
    selectedText: String,
    evalJs: (String, (String?) -> Unit) -> Unit,
    ensureSentenceQuotesReady: suspend () -> Unit,
    sentenceQuotesProvider: () -> Map<String, SentenceQuote>,
    sentenceChaptersProvider: () -> Map<String, String>,
    onPlayFromHere: (String) -> Unit,
) {
    ensureSentenceQuotesReady()
    val scoped = scopeSentencesToChapter(
        sentenceQuotesProvider(), sentenceChaptersProvider(), chapterHref,
    )
    evalJs(resolveSelectionSentenceJs(scoped)) { raw ->
        val geomId = raw?.trim('"')?.takeIf { it.isNotEmpty() }
        val sid = geomId
            ?: ContinuousPositionTracker.sentenceIdForSelection(selectedText, scoped.toMap())
        val ref = if (sid != null) "$chapterHref#$sid" else chapterHref
        onPlayFromHere(ref)
    }
}
