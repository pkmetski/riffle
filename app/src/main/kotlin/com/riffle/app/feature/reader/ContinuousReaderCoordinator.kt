package com.riffle.app.feature.reader

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntRect
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
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
 * All callback lambdas are passed at construction time. They close over the screen's
 * [rememberUpdatedState] delegates, so each invocation reads the latest value rather than the
 * value captured at [remember] time.
 */
internal class ContinuousReaderCoordinator(
    private val publication: Publication,
    private val railSegmentsProvider: () -> List<RailSegment>,
    private val onLocator: (Locator) -> Unit,
    private val onTap: () -> Unit,
    private val latestLocator: () -> Locator?,
    private val onFollowInternalLink: (Link, Locator) -> Unit,
    private val onExternalLink: (url: String) -> Unit,
    private val onFootnote: (FootnoteContent) -> Unit,
    private val onAnnotationTap: (id: String, rect: IntRect) -> Unit,
    private val onAnnotationNoteTap: (id: String, rect: IntRect) -> Unit,
    private val onHighlight: (Locator, IntRect) -> Unit,
    private val sentenceQuotesProvider: () -> Map<String, SentenceQuote>,
    private val sentenceChaptersProvider: () -> Map<String, String>,
    private val onPlayFromHere: (String) -> Unit,
) {
    private var view: ContinuousReaderView? = null

    // Fulfilled when attach() is called. onTocNavigation awaits this to survive the race between
    // the AndroidView factory (layout phase) and the navigation LaunchedEffect (composition phase),
    // which have no guaranteed ordering on first composition.
    private val viewReady = CompletableDeferred<ContinuousReaderView>()

    /**
     * Wire all [ContinuousReaderView] callbacks. Call from the [AndroidView] factory immediately
     * after the view is created.
     */
    fun attach(view: ContinuousReaderView) {
        this.view = view
        viewReady.complete(view)

        view.onRawPosition = { href, progression ->
            val locator = buildContinuousLocator(href, progression, railSegmentsProvider())
            if (locator != null) onLocator(locator)
        }

        view.onTap = { onTap() }

        view.onInternalLinkTapped = { href ->
            val origin = latestLocator()
            val path = href.substringBefore('#')
            val link = publication.readingOrder.firstOrNull { it.href.toString() == path }
            if (link != null && origin != null) {
                onFollowInternalLink(link, origin)
            } else {
                this.view?.navigateTo(href, 0f)
            }
        }

        view.onExternalLinkTapped = { url -> onExternalLink(url) }

        view.onFootnoteContent = { content -> onFootnote(content) }

        view.onAnnotationTap = { _, id, androidRect ->
            onAnnotationTap(id, IntRect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom))
        }

        view.onAnnotationNoteTap = { _, id, androidRect ->
            onAnnotationNoteTap(id, IntRect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom))
        }

        view.onHighlightSelection = { chapterHref, selectedText, progression, selectionScreenRect ->
            val locator = Locator.fromJSON(
                JSONObject()
                    .put("href", chapterHref)
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", progression))
                    .put("text", JSONObject().put("highlight", selectedText)),
            )
            if (locator != null) {
                onHighlight(
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
                if (sid != null) onPlayFromHere("$chapterHref#$sid")
            }
        }
    }

    /**
     * Navigate to a TOC entry or chapter-map segment. Suspends until the view is initialized.
     *
     * Call from the TOC/chapter-map navigation LaunchedEffect when in continuous mode.
     */
    suspend fun onTocNavigation(link: Link) {
        val v = viewReady.await()
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
