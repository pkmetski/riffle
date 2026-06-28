package com.riffle.core.domain

/**
 * Selects the applicable remote set for the open book (ADR 0019) and runs the unified
 * reconciliation [CanonicalSyncCycle] over it — the same code path for single-peer
 * (one remote), two-peer (the multi-link guard), and matched (two ABS peers) cases. This is the
 * strategy [ProgressSyncController] delegates to once it knows the book's [BookSyncState];
 * the open side feeds only into [applicableRemotes], never into the cycle itself.
 *
 * [peerFor] constructs the live [ProgressPeer] for a kind (wiring the network APIs and the
 * [CanonicalPositionTranslator]); it may return `null` when a peer cannot be built this
 * cycle (e.g. a prerequisite went missing), in which case that target is simply skipped.
 */
class ProgressSyncStrategy(
    private val peerFor: (RemoteKind) -> ProgressPeer?,
) {
    suspend fun runCycle(state: BookSyncState, local: LocalCanonical): SyncCycleResult {
        val peers = applicableRemotes(state).mapNotNull { peerFor(it) }
        return CanonicalSyncCycle.run(local, peers)
    }
}
