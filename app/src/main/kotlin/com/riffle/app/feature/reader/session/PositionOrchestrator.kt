@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.PositionSaveCoordinator
import com.riffle.app.feature.reader.computeTotalProgression
import com.riffle.core.domain.ReadingPositionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Owns the canonical reading-position stream — the intake for onPositionChanged and the source of
 * truth for the current locator, its href, and the two progression coordinates.
 *
 * Composes two focused collaborators (extracted per #376):
 *   - [serverJump] — remote-win jump channel + suppress latch + pending server timestamp
 *   - [resumeRestorer] — background-return retry watcher + return anchor
 *
 * The pre-#376 public API is preserved as thin delegations so the ViewModel is not churned by the
 * split.
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

    val serverJump: ServerJumpCoordinator = ServerJumpCoordinator()
    val resumeRestorer: ResumeRestorer = ResumeRestorer(refire = { serverJump.requestJump(it) })

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

    /** Facade — see [ServerJumpCoordinator.serverLocatorEvents]. */
    val serverLocatorEvents: Flow<Locator> get() = serverJump.serverLocatorEvents

    // Private mutable backing; also exposed to the VM via snapshotLastLocator().
    @Volatile private var lastLocator: Locator? = null

    /** True after the navigator emits its first locator (the restored position on open). */
    private var initialLocatorSeen = false

    // ---- Per-book dependencies (set by bindBook) ----

    private var itemId: String = ""
    private var sourceId: String = ""
    private var positionSaveCoordinator: PositionSaveCoordinator<String>? = null
    private var readingPositionStore: ReadingPositionStore? = null
    private var spineCountsJob: kotlinx.coroutines.Job? = null

    /**
     * Bind to a new book. Resets all ephemeral position state and wires the spine-counts backfill.
     * Must be called before [onPositionChanged] fires.
     */
    fun bindBook(
        itemId: String,
        sourceId: String,
        positionSaveCoordinator: PositionSaveCoordinator<String>,
        readingPositionStore: ReadingPositionStore,
        spinePositionCounts: StateFlow<Pair<List<String>, List<Int>>>,
    ) {
        this.itemId = itemId
        this.sourceId = sourceId
        this.positionSaveCoordinator = positionSaveCoordinator
        this.readingPositionStore = readingPositionStore

        // Reset all per-book mutable state
        _currentLocator.value = null
        _currentLocatorHref.value = null
        _currentLocatorProgression.value = null
        _currentLocatorTotalProgression.value = null
        lastLocator = null
        initialLocatorSeen = false
        serverJump.reset()
        resumeRestorer.reset()

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
        resumeRestorer.onPositionEmitted(locator)
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
        // win), persist the CFI but restore the server timestamp the cycle adopted. A genuine user
        // navigation leaves no pending stamp and we let PositionSaveCoordinator stamp `now`.
        val serverJumpStamp = serverJump.consumePendingStamp()
        scope.launch {
            positionSaveCoordinator?.onChanged(locator.toJSON().toString())
            if (serverJumpStamp != null) {
                readingPositionStore?.updateLocalTimestamp(sourceId, itemId, serverJumpStamp)
            }
        }
    }

    // ---- Facade delegations preserved so the VM's call sites don't churn ---------------------

    /** @see ServerJumpCoordinator.requestJump */
    fun requestServerJump(locator: Locator) = serverJump.requestJump(locator)

    /**
     * @see ServerJumpCoordinator.requestJumpWithSuppressCheck. When the jump is honored, also
     * mirror the locator into [lastLocator] so a fast reader-close (before the UI-level
     * navigator.go completes) doesn't cause `onClose` to save the stale pre-jump position and
     * push it back over the fresh server value on the next sync (#528).
     */
    fun requestServerJumpWithSuppressCheck(locator: Locator) {
        if (serverJump.requestJumpWithSuppressCheck(locator)) {
            lastLocator = locator
        }
    }

    /** @see ServerJumpCoordinator.markSuppressNext */
    fun markSuppressNextServerLocator() = serverJump.markSuppressNext()

    /** @see ServerJumpCoordinator.setPendingStamp */
    fun setPendingServerJumpStamp(stamp: Long) = serverJump.setPendingStamp(stamp)

    /** Returns the most-recently-reported locator without consuming it. */
    fun snapshotLastLocator(): Locator? = lastLocator

    /**
     * Directly updates the in-memory last-locator snapshot WITHOUT triggering any persistence or
     * state-update side effects. Used when a sync cycle jumps the reader and needs to update the
     * snapshot so subsequent cycles use the jumped position (not the stale pre-jump position).
     * The actual Readium [onPositionChanged] emission that follows the navigator jump is the one
     * that carries the pending server-jump stamp and triggers persistence.
     */
    fun updateLastLocatorSnapshot(locator: Locator) {
        lastLocator = locator
        _currentLocator.value = locator
    }

    /** @see ResumeRestorer.setReturnAnchor */
    fun setReturnAnchor(locator: Locator?) = resumeRestorer.setReturnAnchor(locator)

    /** @see ResumeRestorer.forceSetReturnAnchor */
    fun forceSetReturnAnchor(locator: Locator?) = resumeRestorer.forceSetReturnAnchor(locator)

    /** @see ResumeRestorer.consumeReturnAnchor */
    fun consumeReturnAnchor(): Locator? = resumeRestorer.consumeReturnAnchor()

    /** @see ResumeRestorer.peekReturnAnchor */
    fun peekReturnAnchor(): Locator? = resumeRestorer.peekReturnAnchor()

    /** @see ResumeRestorer.armReturnRestore */
    fun armReturnRestore(origin: Locator) = resumeRestorer.armReturnRestore(origin)

    /** Resets [initialLocatorSeen] — called when the reader is resumed so the first Readium
     *  emission (the WebView restoration) is not treated as user progress. */
    fun resetInitialLocatorSeen() {
        initialLocatorSeen = false
    }
}
