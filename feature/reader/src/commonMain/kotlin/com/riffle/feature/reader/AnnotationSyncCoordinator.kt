package com.riffle.feature.reader

import com.riffle.core.sync.AnnotationSyncStatusStore
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Banner that communicates annotation-sync status to the reader chrome.
 *
 * Derived from [AnnotationSyncStatusStore.lastCycleOutcome]; the coordinator maps domain outcomes
 * to these UI tokens so the screen stays decoupled from the sync internals.
 */
sealed class AnnotationSyncBanner {
    /** Last cycle succeeded. */
    object Synced : AnnotationSyncBanner()

    /** Last cycle failed (network, auth, etc.). */
    data class Failed(val message: String?) : AnnotationSyncBanner()
}

/**
 * Maps a sync-cycle outcome to the reader's banner token. `null` = no cycle has run yet
 * (initial state; nothing to show).
 */
fun CycleOutcome.toAnnotationSyncBanner(): AnnotationSyncBanner? = when (this) {
    is CycleOutcome.NeverRun -> null
    is CycleOutcome.Success -> AnnotationSyncBanner.Synced
    is CycleOutcome.Failed -> AnnotationSyncBanner.Failed(
        when (this) {
            is CycleOutcome.Failed.Network -> message
            is CycleOutcome.Failed.Auth -> "Authentication failed ($code)"
            is CycleOutcome.Failed.Tls -> message
            is CycleOutcome.Failed.Server -> "Source error ($code)"
            is CycleOutcome.Failed.Unknown -> message
        },
    )
}

/**
 * Owns the annotation-sync *lifecycle* for one open book: which book is bound, whether its
 * cross-device sync namespace has resolved yet, and the single-flight live-pull [Job].
 *
 * Extracted from `AnnotationSession` (issue #1066) because none of this is platform-bound —
 * it is book identity, namespace gating and coroutine-job bookkeeping. `AnnotationSession`
 * stays in `:app` while it is parameterised by Readium's `Locator` and Compose's `IntRect`;
 * this half moves to `commonMain` so iOS's annotation session reuses the same rules and the
 * same tests cover both platforms.
 *
 * All four sync operations are injected as lambdas because their implementations live in the
 * hosting ViewModel (they need the catalog/source plumbing), exactly as they did before.
 */
class AnnotationSyncCoordinator(
    private val scope: CoroutineScope,
    private val progressFlushScope: ProgressFlushScope,
    /** Returns the [Job] backing the live-pull loop. */
    private val startLiveSync: (sourceId: String, namespace: String, itemId: String) -> Job,
    /** Schedules a debounced push. */
    private val scheduleSync: (sourceId: String, namespace: String, itemId: String) -> Unit,
    /** Open-time pull of peer annotations. */
    private val syncOnOpen: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
    /** Close-time push, run on [ProgressFlushScope] so it survives teardown. */
    private val syncOnClose: suspend (sourceId: String, namespace: String, itemId: String) -> Unit,
) {

    /** Active book identity, set by [bind]. Null between books. */
    private var boundSourceId: String? = null

    /**
     * Cross-device sync namespace for the bound book. May be left blank at [bind] time when the
     * caller resolves the namespace off the critical path (see [updateNamespace]) — sync
     * scheduling treats a null/blank value as "not yet ready" and no-ops until [updateNamespace]
     * fills it in. The annotation-observer path never reads this, so binding early with a blank
     * still starts the annotation Flow subscription correctly.
     */
    private var boundNamespace: String? = null
    private var boundItemId: String? = null

    /** The live-pull job for the current book. Cancelled on [bind] / [onBookClosed]. */
    private var liveSyncJob: Job? = null

    /** Book identity of the currently bound book, or null before the first [bind]. */
    val sourceId: String? get() = boundSourceId
    val itemId: String? get() = boundItemId

    /**
     * Reflects the last annotation sync outcome as a UI banner. Null = no cycle has run yet
     * (initial state; nothing to show).
     */
    fun syncBanner(statusStore: AnnotationSyncStatusStore): StateFlow<AnnotationSyncBanner?> =
        statusStore.lastCycleOutcome
            .map { it.toAnnotationSyncBanner() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Bind to a new book: cancel the previous live-pull loop (single-flight guarantee) and record
     * the new identity. An empty [namespace] means "not resolved yet" — see [updateNamespace].
     *
     * Deliberately does NOT start the open-time sync; the caller does that with
     * [startSyncForBoundBook] *after* it has started its own observers. The ordering is
     * observable under an unconfined dispatcher, so the two phases stay separate.
     */
    fun bind(sourceId: String, namespace: String, itemId: String) {
        liveSyncJob?.cancel()
        liveSyncJob = null
        boundSourceId = sourceId
        boundNamespace = namespace.takeIf { it.isNotEmpty() }
        boundItemId = itemId
    }

    /**
     * Sync on open: pull peer annotations, then start the live-pull loop. Skipped when the caller
     * invoked [bind] with an empty namespace and hasn't yet supplied a real one via
     * [updateNamespace] — the reader-open path uses that pattern to start the annotation observer
     * eagerly while the namespace resolves in parallel. Once [updateNamespace] lands a real
     * namespace, it re-runs this bootstrap itself.
     */
    fun startSyncForBoundBook() {
        val sid = boundSourceId ?: return
        val ns = boundNamespace ?: return
        val iid = boundItemId ?: return
        scope.launch {
            syncOnOpen(sid, ns, iid)
            liveSyncJob = startLiveSync(sid, ns, iid)
        }
    }

    /**
     * Late-arriving sync-namespace supply for callers that ran [bind] with an empty namespace
     * (typically because the namespace resolves off the reader-open critical path). No-op if the
     * value hasn't changed or the caller passes blank. Kicks off the same syncOnOpen +
     * startLiveSync bootstrap the eager-bind path runs.
     */
    fun updateNamespace(namespace: String?) {
        val fresh = namespace?.takeIf { it.isNotEmpty() }
        val sourceId = boundSourceId ?: return
        val itemId = boundItemId ?: return
        val previous = boundNamespace
        if (fresh == previous) return
        boundNamespace = fresh
        // Only bootstrap sync if this is the transition from "no namespace" → "have namespace".
        // A namespace CHANGE mid-session (unlikely — same book, same server) intentionally does
        // not restart the live-sync loop; only a full [bind] does.
        if (fresh != null && previous == null && liveSyncJob == null) {
            scope.launch {
                syncOnOpen(sourceId, fresh, itemId)
                // Nudge a debounced push after arrival: any user mutation (createHighlight,
                // deleteAnnotation, recolour, note edit) that happened in the race window between
                // bind(namespace="") and this updateNamespace call short-circuited its
                // scheduleSync at `boundNamespace ?: return`. Its Room write persists, but the
                // remote push was never queued. Firing scheduleSync here restarts the debounce
                // so the pending local writes get pushed on the next natural tick.
                scheduleSync(sourceId, fresh, itemId)
                liveSyncJob = startLiveSync(sourceId, fresh, itemId)
            }
        }
    }

    /**
     * Schedule a debounced push for the bound book. No-op until [bind] has run AND the namespace
     * has resolved — the callers' historical `scheduleSync(boundSourceId ?: return, …)` idiom.
     */
    fun scheduleSyncIfReady() {
        scheduleSync(boundSourceId ?: return, boundNamespace ?: return, boundItemId ?: return)
    }

    /**
     * Cancel the live-sync polling loop when the reader is backgrounded. The complementary
     * [onReaderResumed] restarts it. Together they form the lifecycle gate the original VM
     * implemented as "STARTED gating" — preserves the same network/battery behaviour.
     *
     * No-op if [bind] has not been called for a synced book.
     */
    fun onReaderClosed() {
        liveSyncJob?.cancel()
        liveSyncJob = null
    }

    /**
     * Called when the reader is resumed from background. Re-arms the live-pull loop so peer
     * annotations remain fresh throughout a foreground session. The open-time syncOnOpen is
     * NOT repeated — only the periodic live-pull is restarted.
     *
     * No-op if [bind] has not been called for a synced book (no bound source).
     */
    fun onReaderResumed() {
        val sid = boundSourceId ?: return
        val ns = boundNamespace ?: return
        val iid = boundItemId ?: return
        if (ns.isBlank()) return // namespace not resolved — sync not configured this session
        liveSyncJob?.cancel()
        liveSyncJob = startLiveSync(sid, ns, iid)
    }

    /**
     * Called when the reader is closed (VM cleared). Cancels the live-sync loop and pushes any
     * pending annotations on [ProgressFlushScope] so the write survives scope cancellation at
     * teardown.
     */
    fun onBookClosed() {
        liveSyncJob?.cancel()
        liveSyncJob = null
        val sid = boundSourceId ?: return
        val ns = boundNamespace ?: return
        val iid = boundItemId ?: return
        progressFlushScope.flush { syncOnClose(sid, ns, iid) }
    }
}
