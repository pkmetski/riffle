package com.riffle.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceEvent
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.PauseCause
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.feature.reader.resolveCadenceStartRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The host-independent half of a Cadence reading session: everything between "the WebView just
 * told us what this chapter's sentences are" and "the ticker is highlighting the right one".
 *
 * It exists because both readers need exactly this and the logic is not platform-shaped. It used
 * to live inline in Android's `EpubReaderViewModel`; iOS would otherwise have grown a second copy
 * of the cross-chapter quote merge, the `DomSentenceSource` rebind and the start-ref resolution —
 * the private-platform-copy failure mode AGENTS.md calls out, with the added twist that the two
 * copies would silently start Cadence on different sentences.
 *
 * What stays with the host: running the JavaScript (Android's `RendererBridge`, iOS's
 * `ReadiumSwiftNavigator`), painting the decoration, and the mutual-exclusion fan-out, which needs
 * each host's own Readaloud/Auto-Scroll handles.
 */
class CadenceSession(
    private val controller: CadenceController,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    /**
     * Write a nudged speed back to the host's preference store. Both hosts supply one — Android
     * through `FormattingSession.updateFormatting`, iOS through `FormattingPreferencesStore` —
     * because a nudge that only moves the live ticker is forgotten the moment the reader closes,
     * which is exactly what Cadence did while Auto-Scroll's identical gesture persisted.
     */
    private val persistWpm: (Int) -> Unit = {},
) {
    val state: StateFlow<CadenceState> = controller.state
    val currentFragment: StateFlow<FragmentRef?> = controller.currentFragment
    val currentProgress: StateFlow<Double?> = controller.currentProgress

    private val _quotes = MutableStateFlow<Map<FragmentRef, SentenceQuote>>(emptyMap())

    /** Every sentence tokenised this session, across chapters, in reading order. */
    val quotes: StateFlow<Map<FragmentRef, SentenceQuote>> = _quotes.asStateFlow()

    private var mergedChapterHrefs: Map<FragmentRef, String> = emptyMap()

    /** `FragmentRef → chapter href`, the map [resolveCadenceStartRef] resolves a start against. */
    val chapterHrefs: Map<FragmentRef, String> get() = mergedChapterHrefs

    private val _endOfChapterEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires when the ticker drains the current chapter's ordering. The host advances one chapter
     * forward; the state stays [CadenceState.Running], so the next [onChapterTokenised] resumes
     * ticking with no user tap.
     */
    val endOfChapterEvents: SharedFlow<Unit> = _endOfChapterEvents.asSharedFlow()

    /**
     * Keep the controller's default speed following the user's `cadenceWpm` preference.
     *
     * Before this existed, [CadenceController.setDefaultSpeed] had no production caller on either
     * platform: the preference round-tripped to storage and the Settings slider moved, but every
     * session started at [AutoScrollSpeed.Default] (250 wpm) regardless. Auto-Scroll's equivalent
     * binding lives in Android's `FormattingSession`; this one is shared so the two hosts cannot
     * drift again.
     */
    fun bindDefaultSpeed(wpm: Flow<Int>) {
        scope.launch {
            wpm.distinctUntilChanged().collect { controller.setDefaultSpeed(AutoScrollSpeed.of(it)) }
        }
    }

    /**
     * Accept a freshly tokenised chapter and rebind the ticker over the accumulated sentence map.
     *
     * Quotes accumulate across every tokenised chapter — Android's Continuous mode keeps several
     * chapters in its sliding window, and both readers re-tokenise the current chapter on reflow.
     * Insertion order is preserved so the ticker's fragment ordering stays reading-order stable
     * across rebinds.
     */
    fun onChapterTokenised(
        quotes: Map<FragmentRef, SentenceQuote>,
        hrefs: Map<FragmentRef, String>,
    ) {
        val merged = LinkedHashMap(_quotes.value).apply { putAll(quotes) }
        _quotes.value = merged
        mergedChapterHrefs = LinkedHashMap(mergedChapterHrefs).apply { putAll(hrefs) }
        logger?.d(LogChannel.Cadence) {
            "CadenceSession.onChapterTokenised chapterQuotes=${quotes.size} totalQuotes=${merged.size}"
        }
        val source = DomSentenceSource().apply { supplyResult(merged, mergedChapterHrefs) }
        scope.launch {
            controller.bind(source, onExhausted = { _endOfChapterEvents.tryEmit(Unit) })
        }
    }

    /**
     * Start the ticker at the sentence the host's page-top probe resolved.
     *
     * [probedFragmentId] is `"cd-N"`, `"chapter#cd-N"`, or null when nothing could be located.
     * Resolution is [resolveCadenceStartRef]'s; falling through to the ticker's own
     * `orderedFragments[0]` would start at the first sentence of whichever chapter was tokenised
     * first this session, usually several pages behind the reader.
     */
    fun onPageTopResolved(href: String, probedFragmentId: String?) {
        val startRef = resolveCadenceStartRef(
            href = href,
            probedFragmentId = probedFragmentId,
            chapterHrefs = mergedChapterHrefs,
            knownRefs = _quotes.value.keys,
        )
        logger?.d(LogChannel.Cadence) {
            "CadenceSession.onPageTopResolved href=$href fragmentId=$probedFragmentId → startRef=$startRef"
        }
        if (startRef != null) controller.goTo(startRef)
        controller.dispatch(CadenceEvent.Start)
    }

    /** Start with no probe result — the ticker falls back to its own first fragment. */
    fun startWithoutProbe() {
        controller.dispatch(CadenceEvent.Start)
    }

    fun stop() {
        controller.dispatch(CadenceEvent.Stop)
    }

    fun resumeIfPaused() {
        controller.dispatch(CadenceEvent.Resume)
    }

    fun pauseFor(cause: PauseCause) {
        controller.pauseFor(cause)
    }

    fun setPaused(paused: Boolean, cause: PauseCause) {
        controller.setPaused(paused, cause)
    }

    /**
     * Nudge the live speed by [by] wpm and persist the result through [persistWpm].
     *
     * [storedWpm] is what the preference currently holds; nothing is written when the nudge did
     * not change it, or when there is no active session for the nudge to act on.
     */
    fun nudge(by: Int, storedWpm: Int) {
        controller.nudgeSpeedAndPersistableWpm(by, storedWpm)?.let(persistWpm)
    }

    /** Tear the session down when the book closes: ticker off, accumulated sentences dropped. */
    fun reset() {
        controller.unbind()
        _quotes.value = emptyMap()
        mergedChapterHrefs = emptyMap()
    }
}
