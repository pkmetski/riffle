package com.riffle.app.feature.reader.sentence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.riffle.app.feature.reader.HighlightRenderer
import com.riffle.app.feature.reader.presenter.ReadaloudFollowResult
import com.riffle.app.feature.reader.presenter.ReaderPresenter
import com.riffle.app.feature.reader.presenter.ReadiumPresenter
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.SentenceQuote
import org.readium.r2.shared.publication.Locator

/**
 * Owns the two sentence-highlight `LaunchedEffect`s that used to live inline in
 * `EpubReaderScreen`: applying the synced highlight decoration, and auto-following the narrated
 * sentence (scroll-centering in Vertical, column-snap in Paginated). Extracted per ADR 0039 so a
 * future non-Readaloud driver (Cadence) can attach the same pipeline.
 *
 * This task (Task 6) keeps the data flow exactly as it was: [sentenceQuotes] and [activeFragmentRef]
 * are still supplied by the ViewModel's existing Readaloud pipeline (via [SidecarSentenceSource]
 * being present-but-unused) — this controller does not yet call `SentenceSource.loadAll()` itself.
 * That migration is left to a follow-up once a second [com.riffle.core.domain.sentence.SentenceSource]
 * exists.
 *
 * [fragmentLocator] mirrors the file-private `EpubReaderScreen.fragmentLocator` helper — it can't be
 * called directly from this package, so the screen passes an equivalent lambda.
 */
internal class SentencePlaybackController(
    private val highlightRenderer: () -> HighlightRenderer,
    private val readerPresenter: () -> ReaderPresenter,
    private val readiumPresenter: () -> ReadiumPresenter?,
    private val fragmentLocator: (ref: String, quote: SentenceQuote?) -> Locator?,
) {

    /**
     * Hosts the sentence-highlight and auto-follow `LaunchedEffect`s. Every parameter mirrors a key
     * from the original inline effects — do not drop one, or the corresponding stale-highlight /
     * missed-reflow bug this key list guards against will resurface.
     */
    @Composable
    fun Attach(
        activeFragmentRef: String?,
        sentenceQuotes: Map<String, SentenceQuote>,
        readaloudHighlightColor: HighlightColor,
        reflowGeneration: Int,
        pageLoadGeneration: Int,
    ) {
        // ---- Readaloud synced highlight -----------------------------------------------------
        // Superset keys cover both Readium (pageLoadGeneration, reflowGeneration re-apply on
        // reflow/rotation) and Continuous (sentenceQuotes re-applies when quotes build
        // asynchronously). The [highlightRenderer] key picks up an orientation flip: the renderer
        // is recreated Readium<->Continuous and the fresh instance has to receive the current
        // sentence immediately, not on the next reflow tick.
        LaunchedEffect(
            highlightRenderer(),
            activeFragmentRef,
            reflowGeneration,
            pageLoadGeneration,
            sentenceQuotes,
            readaloudHighlightColor,
        ) {
            highlightRenderer().applySentenceHighlight(activeFragmentRef, sentenceQuotes, readaloudHighlightColor)
        }

        // ---- Auto-follow: keep the narrated sentence on screen ------------------------------
        // Playback drives activeFragmentRef forward (audio-clock, one change per narrated
        // sentence); the page should follow the narrated sentence. Readium 3.0.0 can't enumerate
        // visible fragments, so we ask the WebView for the element's on-screen rect and act per
        // layout:
        //
        //  - Scroll (Vertical) mode - the document overflows the viewport, so we scroll it to
        //    KEEP THE SENTENCE CENTERED, the natural karaoke-follow.
        //  - Paginated (Horizontal) mode - each page is exactly viewport-sized, KEEP-VISIBLE
        //    follow: while the narrated sentence's start is on the current page the probe leaves
        //    the page in place, and only flips (snaps scrollLeft onto the column grid) once the
        //    sentence's start moves off the current page. This is what stops starting playback -
        //    and the player-open reflow that re-runs this probe - from yanking the line the user
        //    pressed onto a fresh column boundary. The snap holds because the reader is sized so
        //    innerWidth == Readium's page-snap pitch, so floor(x / innerWidth) * innerWidth is
        //    exactly a column boundary.
        //
        // A missing element (sentence in another chapter's document) reads as "off" -> go(locator)
        // jumps chapters, so cross-chapter follow falls out for free in both modes.
        //
        // Re-keys on reflowGeneration (formatting reflows) and pageLoadGeneration (rotation /
        // chapter load) so the narrated sentence is re-centred after those relayouts. The player
        // floats over the page and no longer reflows it, so opening it doesn't move the narrated
        // sentence's column.
        LaunchedEffect(activeFragmentRef, sentenceQuotes, reflowGeneration, pageLoadGeneration) {
            performAutoFollow(
                activeFragmentRef = activeFragmentRef,
                sentenceQuotes = sentenceQuotes,
                followReadaloudSentence = { text -> readerPresenter().followReadaloudSentence(text) },
                fragmentLocator = fragmentLocator,
                navigateToLocator = { locator -> readiumPresenter()?.navigateToLocator(locator, snap = false, animated = false) },
            )
        }
    }
}

/**
 * Pure decision body of the auto-follow `LaunchedEffect`: resolves [activeFragmentRef] to a
 * [SentenceQuote], asks the presenter to follow it on-screen, and falls back to a text-anchored
 * navigation when the sentence isn't on the currently-rendered resource (cross-chapter case).
 *
 * Extracted as a standalone top-level function (rather than left inline in the `LaunchedEffect`)
 * so it is unit-testable on the JVM without a Compose test harness — see
 * `SentencePlaybackControllerTest`.
 */
internal suspend fun performAutoFollow(
    activeFragmentRef: String?,
    sentenceQuotes: Map<String, SentenceQuote>,
    followReadaloudSentence: suspend (text: String) -> ReadaloudFollowResult,
    fragmentLocator: (ref: String, quote: SentenceQuote?) -> Locator?,
    navigateToLocator: suspend (Locator) -> Unit,
) {
    val ref = activeFragmentRef ?: return
    if (ref.indexOf('#') < 0) return
    // Cadence fragments carry ids of the form "cd-N" written by CadenceDomScript's tokeniser.
    // Cadence's page-top probe (rendererBridge.firstVisibleCadenceSpanId) already picked a span
    // whose bounding rect is inside the viewport, so a text-search-based follow can only make
    // things worse: for short Cadence sentences (e.g. "the problems.") the text-search will hit
    // an earlier DOM occurrence and navigateToLocator would then jump the reader elsewhere.
    // Readaloud fragments have ids like "sN" and continue to use the follow.
    val sid = ref.substringAfter('#', "")
    if (sid.startsWith("cd-")) return
    // No quote yet (the map is built off-thread once playback starts) -> we can neither locate the
    // sentence by text nor anchor a go(): the cssSelector-only locator can't resolve on the
    // span-stripped ABS page, so a snap would flip to chapter start. Skip until the quote arrives;
    // the caller re-keys on the quotes map and re-runs to follow correctly once it's available.
    // Try full ref (Cadence: "href#cd-N") before sid alone (Readaloud: "sN"). Same pipeline.
    val quote = sentenceQuotes[ref] ?: sentenceQuotes[ref.substringAfter('#', "")] ?: return
    // Locate the sentence by its text (spans are stripped). The probe snaps to the sentence's
    // column itself in paginated mode; OffPage comes back only when the text isn't on this resource
    // (another chapter), where we fall back to a text-anchored go() to load it. Vertical / continuous
    // adapters report Unavailable - their per-sentence follow lives elsewhere.
    when (followReadaloudSentence(quote.highlight)) {
        ReadaloudFollowResult.Snapped, ReadaloudFollowResult.Unavailable -> Unit
        ReadaloudFollowResult.OffPage ->
            fragmentLocator(ref, quote)?.let { navigateToLocator(it) }
    }
}
