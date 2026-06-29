@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.PositionSaveCoordinator
import com.riffle.app.feature.reader.computeTotalProgression
import com.riffle.core.domain.ReadingPositionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Owns the canonical reading-position stream. Lifted from EpubReaderViewModel as part of the VM
 * split (#303).
 *
 * Holds all the hot-path fields that were previously bare private vars on the VM:
 * [lastLocator], [_serverLocatorChannel], [pendingServerJumpStamp], [_currentLocatorHref],
 * [_currentLocatorProgression], [_currentLocatorTotalProgression], [suppressNextServerLocator],
 * [initialLocatorSeen], [pendingReturnLocator], [returnRestoreAttempts].
 *
 * The VM's public [onPositionChanged] delegates here after handling readaloud "park" state (Task 8
 * code) which remains in the VM until ReadaloudSession is extracted.
 *
 * MUST NOT import android.webkit.* or ContinuousReaderView.
 */
class PositionOrchestrator @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): PositionOrchestrator
    }

    // ---- Per-book state (reset by bindBook) ----

    /** The most-recently-reported Locator; null before the first onPositionChanged fires. */
    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator

    private val _currentLocatorHref = MutableStateFlow<String?>(null)
    val currentLocatorHref: StateFlow<String?> = _currentLocatorHref

    private val _currentLocatorProgression = MutableStateFlow<Float?>(null)
    val currentLocatorProgression: StateFlow<Float?> = _currentLocatorProgression

    /**
     * Whole-book progress (0..1) for the reading "% read" label — the same coordinate persisted as
     * ebookProgress and shown in book details. Distinct from railCursorPosition. Updated only when
     * the navigator emits a non-null totalProgression.
     */
    private val _currentLocatorTotalProgression = MutableStateFlow<Float?>(null)
    val currentLocatorTotalProgression: StateFlow<Float?> = _currentLocatorTotalProgression

    /**
     * Carries server-win jumps (sync cycle + Storyteller loop + background-return restore) to the
     * EpubNavigatorFragment. A conflated channel so a request survives until the screen's collector
     * receives it — a reopen can emit before the collector re-subscribes after a config change.
     */
    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    // Private mutable backing; also exposed to the VM via snapshotLastLocator().
    @Volatile private var lastLocator: Locator? = null

    /**
     * True when this reader was opened with an explicit openAtCfi (e.g. a library annotation tap or
     * a search-result jump). Consumed (and cleared) by [requestServerJumpWithSuppressCheck].
     */
    @Volatile private var suppressNextServerLocator: Boolean = false

    /** True after the navigator emits its first locator (the restored position on open). */
    private var initialLocatorSeen = false

    /**
     * Set to the winning server timestamp when the cycle drives a remote-win jump; consumed by the
     * jump's resulting onPositionChanged. That emission persists the CFI but keeps this server
     * timestamp instead of stamping `now`.
     */
    @Volatile private var pendingServerJumpStamp: Long? = null

    /**
     * The locator to restore on [onReaderResumed]. Armed in [onReaderClosed] from [lastLocator];
     * the footnote-popup URL-tap path pre-arms it earlier via [setReturnAnchor].
     */
    private var pendingReturnLocator: Locator? = null

    /**
     * How many remaining onPositionChanged → chapter-top emissions to suppress while restoring
     * after a background return.
     */
    private var returnRestoreAttempts = 0

    // ---- Per-book dependencies (set by bindBook) ----

    private var itemId: String = ""
    private var serverId: String = ""
    private var positionSaveCoordinator: PositionSaveCoordinator<String>? = null
    private var readingPositionStore: ReadingPositionStore? = null
    private var spineCountsJob: kotlinx.coroutines.Job? = null

    /**
     * Bind to a new book. Resets all ephemeral position state and wires the spine-counts backfill.
     * Must be called before [onPositionChanged] fires.
     */
    fun bindBook(
        itemId: String,
        serverId: String,
        positionSaveCoordinator: PositionSaveCoordinator<String>,
        readingPositionStore: ReadingPositionStore,
        spinePositionCounts: StateFlow<Pair<List<String>, List<Int>>>,
    ) {
        this.itemId = itemId
        this.serverId = serverId
        this.positionSaveCoordinator = positionSaveCoordinator
        this.readingPositionStore = readingPositionStore

        // Reset all per-book mutable state
        _currentLocator.value = null
        _currentLocatorHref.value = null
        _currentLocatorProgression.value = null
        _currentLocatorTotalProgression.value = null
        lastLocator = null
        suppressNextServerLocator = false
        initialLocatorSeen = false
        pendingServerJumpStamp = null
        pendingReturnLocator = null
        returnRestoreAttempts = 0

        // Back-fill totalProgression when spine position counts arrive after the first position event.
        // In continuous mode the initial scroll may fire before positions load, leaving
        // _currentLocatorTotalProgression null. When counts become non-empty, recompute from the
        // last known href + within-resource progression so time-remaining and rail cursor update
        // immediately.
        spineCountsJob?.cancel()
        spineCountsJob = scope.launch {
            spinePositionCounts.collect { (spineHrefs, counts) ->
                if (counts.isEmpty() || _currentLocatorTotalProgression.value != null) return@collect
                val href = _currentLocatorHref.value ?: return@collect
                val cp = _currentLocatorProgression.value ?: return@collect
                computeTotalProgression(href, cp, spineHrefs, counts)?.let {
                    _currentLocatorTotalProgression.value = it
                }
            }
        }
    }

    /**
     * Hot path — called by the VM's [onPositionChanged] delegate (which first handles readaloud
     * park-state, a Task 8 concern). Semantics are lifted verbatim from the VM; do NOT simplify.
     *
     * The auto-memory [reference_continuous_annotation_focus_reflow_race.md] documents how
     * reflow-race fixes live in this path — all of that logic is preserved as-is.
     */
    fun onPositionChanged(
        locator: Locator,
        spineHrefs: List<String> = emptyList(),
        spineCounts: List<Int> = emptyList(),
    ) {
        // Defensive re-restore: Readium's post-resume column-snap can emit a chapter-top position
        // AFTER our [onReaderResumed] navigateTo, sometimes more than once — including a delayed
        // clobber that arrives AFTER a first emission has already landed at the captured origin.
        // Stay armed (within the attempt budget) as long as we're parked at-or-near the origin;
        // only disarm when the user clearly navigates AWAY (different href, or progression past
        // origin). An emission AT origin means this round took, not that future emissions can't
        // re-clobber us, so don't clear pending on equality.
        returnRestoreAttempts.let { remaining ->
            if (remaining > 0) {
                val pending = pendingReturnLocator
                if (pending != null) {
                    val originHref = pending.href.toString()
                    val originProg = pending.locations.progression ?: 0.0
                    val incomingHref = locator.href.toString()
                    val incomingProg = locator.locations.progression ?: 0.0
                    when {
                        incomingHref == originHref && incomingProg < originProg - 0.01 -> {
                            // Spurious chapter-top emission while we're still restoring — re-fire.
                            returnRestoreAttempts = remaining - 1
                            _serverLocatorChannel.trySend(pending)
                        }
                        incomingHref != originHref || incomingProg > originProg + 0.01 -> {
                            // User navigated away — stop watching.
                            returnRestoreAttempts = 0
                            pendingReturnLocator = null
                        }
                        // else: incoming ≈ origin — restore took this round; stay armed in case
                        // a delayed column-snap re-clobbers before the budget is exhausted.
                    }
                } else {
                    returnRestoreAttempts = 0
                }
            }
        }
        lastLocator = locator
        _currentLocator.value = locator
        _currentLocatorHref.value = locator.href.toString()
        val cp = locator.locations.progression?.toFloat()
        _currentLocatorProgression.value = cp
        // Prefer totalProgression from the locator (Readium sets it in paginated/vertical modes).
        // In continuous mode the locator is built by buildContinuousLocator which derives it from
        // the spine's per-resource position counts; if counts are empty at scroll time the field
        // is absent. Fall back to an inline computation so the value is always populated when
        // position counts are already available.
        val tp = locator.locations.totalProgression?.toFloat()
            ?: cp?.let { computeTotalProgression(locator.href.toString(), it, spineHrefs, spineCounts) }
        tp?.let { _currentLocatorTotalProgression.value = it }
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        // If this emission is the reader settling onto a position the cycle jumped it to (a remote
        // win), persist the CFI but restore the server timestamp the cycle adopted — see
        // pendingServerJumpStamp. A genuine user navigation leaves the flag null and stamps `now`.
        val serverJumpStamp = pendingServerJumpStamp
        pendingServerJumpStamp = null
        scope.launch {
            positionSaveCoordinator?.onChanged(locator.toJSON().toString())
            if (serverJumpStamp != null) {
                readingPositionStore?.updateLocalTimestamp(serverId, itemId, serverJumpStamp)
            }
        }
    }

    /**
     * Send [locator] through the server-locator channel unconditionally. Used by sync cycles that
     * want to jump the reader to a remote-win position.
     */
    fun requestServerJump(locator: Locator) {
        _serverLocatorChannel.trySend(locator)
    }

    /**
     * Variant used by the progressSyncController observer: honours [suppressNextServerLocator]
     * and clears it once consumed, so an explicit openAtCfi navigation is not overridden.
     */
    fun requestServerJumpWithSuppressCheck(locator: Locator) {
        if (suppressNextServerLocator) {
            suppressNextServerLocator = false
            return
        }
        _serverLocatorChannel.trySend(locator)
    }

    /**
     * Mark that the next server-locator event should be suppressed. Called from [openBook] when
     * openAtCfi is resolved, before the sync starts.
     */
    fun markSuppressNextServerLocator() {
        suppressNextServerLocator = true
    }

    /** Called when [pendingServerJumpStamp] should be set from a sync cycle result. */
    fun setPendingServerJumpStamp(stamp: Long) {
        pendingServerJumpStamp = stamp
    }

    /** Returns the most-recently-reported locator without consuming it. */
    fun snapshotLastLocator(): Locator? = lastLocator

    /**
     * Directly updates the in-memory last-locator snapshot WITHOUT triggering any persistence or
     * state-update side effects. Used when a sync cycle jumps the reader and needs to update the
     * snapshot so subsequent cycles use the jumped position (not the stale pre-jump position).
     * The actual Readium [onPositionChanged] emission that follows the navigator jump is the one
     * that carries the [pendingServerJumpStamp] and triggers persistence.
     */
    fun updateLastLocatorSnapshot(locator: Locator) {
        lastLocator = locator
        _currentLocator.value = locator
    }

    /**
     * Sets the pending return anchor — the locator the reader should restore to after returning
     * from background. Called from [captureFootnotePopupLinkOrigin] and [onReaderClosed].
     * Does NOT overwrite an existing non-null value (the footnote-popup URL-tap pre-arms with the
     * pre-popup origin; [onReaderClosed] must not clobber that with the popup-nudged lastLocator).
     */
    fun setReturnAnchor(locator: Locator?) {
        if (pendingReturnLocator == null) pendingReturnLocator = locator
    }

    /**
     * Forcibly overwrites the return anchor. Used by [captureFootnotePopupLinkOrigin] which
     * explicitly captures the pre-popup origin.
     */
    fun forceSetReturnAnchor(locator: Locator?) {
        pendingReturnLocator = locator
    }

    /**
     * Consume the pending return anchor and clear it. Returns null if none was set.
     */
    fun consumeReturnAnchor(): Locator? {
        val current = pendingReturnLocator
        pendingReturnLocator = null
        return current
    }

    /** Read the pending return anchor without clearing it. */
    fun peekReturnAnchor(): Locator? = pendingReturnLocator

    /**
     * Arms the resume restore that [onReaderResumed] uses: stores [origin] as the pending return
     * locator (so the retry watcher in [onPositionChanged] can access it) and emits it through the
     * server-locator channel so the navigator jumps there. Sets [returnRestoreAttempts] = 5.
     */
    fun armReturnRestore(origin: Locator) {
        pendingReturnLocator = origin
        returnRestoreAttempts = 5
        _serverLocatorChannel.trySend(origin)
    }

    /** Resets [initialLocatorSeen] — called when the reader is resumed so the first Readium
     *  emission (the WebView restoration) is not treated as user progress. */
    fun resetInitialLocatorSeen() {
        initialLocatorSeen = false
    }
}
