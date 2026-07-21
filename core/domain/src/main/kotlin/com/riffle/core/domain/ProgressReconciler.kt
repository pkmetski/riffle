package com.riffle.core.domain

/** Outcome of one single-target reconcile (ADR 0030). */
sealed interface ReconcileOutcome<out P> {
    /** The server position was newer and has been persisted into the local store. */
    data class ServerWon<P>(val position: P, val stamp: Long) : ReconcileOutcome<P>
    /** The local position was newer and was pushed; the row is now clean at [stamp]. */
    data class LocalPushed(val stamp: Long) : ReconcileOutcome<Nothing>
    /** Already in sync; the row was (re)marked clean. */
    data object InSync : ReconcileOutcome<Nothing>
    /** The remote could not be read; the row is left dirty for the next sweep. */
    data object Offline : ReconcileOutcome<Nothing>
    /** The push failed; the row is left dirty for the next sweep. */
    data object PushFailed : ReconcileOutcome<Nothing>
    /** A concurrent local edit advanced the row mid-flight; the conclusion was abandoned, row left dirty. */
    data object Superseded : ReconcileOutcome<Nothing>
}

/**
 * Sink for the UI-facing progress columns (`library_items.readingProgress`, `finishedAt`) that
 * mirror the just-pulled server state on a ServerWon reconcile. The position stores keep only
 * locators / seconds; without this sink the library grid and detail view would stay stale until
 * the reader was reopened and the reader-close path recomputed the fraction. Kept as a fun
 * interface so the domain layer stays pure — Room-backed impl lives in `core/data`.
 */
fun interface UiProgressSink {
    suspend fun apply(sourceId: String, itemId: String, readingProgress: Float, finishedAt: Long?)
}

/**
 * The durable single-target reconcile primitive of ADR 0030: GET-before-PATCH last-update-wins for
 * one ABS record, with compare-and-clear so a concurrent offline edit is never overwritten or lost.
 * Pure orchestration over a [SyncPositionStore] and a per-row [ProgressRemote] — no Android, Room, or
 * network. Used directly by the headless sweep worker (single target) and composed by the live
 * multi-peer cycle.
 *
 * On ServerWon, invokes [uiSink] with the server-derived UI fields so the library grid / detail
 * view re-emit without waiting for the reader to reopen. Default is a no-op for tests / callers
 * that don't render a library UI (live open-book path stays untouched — it uses the stores
 * directly, not this reconciler).
 */
class ProgressReconciler<P>(
    private val store: SyncPositionStore<P>,
    private val uiSink: UiProgressSink = UiProgressSink { _, _, _, _ -> },
) {

    suspend fun reconcile(sourceId: String, itemId: String, remote: ProgressRemote<P>): ReconcileOutcome<P> {
        val snap = store.snapshot(sourceId, itemId)
        val read = remote.get() ?: return ReconcileOutcome.Offline

        // #528 device-clock-skew data-loss guard: a CLEAN row (localUpdatedAt == lastSyncedAt)
        // has nothing new to push — everything the reader last saved has already been synced.
        // But if the device wall clock runs slightly ahead of the ABS server clock, the local
        // stamp we recorded from the last successful sync can end up numerically ABOVE the
        // server's current lastUpdate, even when the server has since been advanced by another
        // device. A naive `localUpdatedAt > lastUpdate` comparison then decides LocalWins and
        // PUSHes the stale local locator over the server's fresh one — the "Device 2 open
        // silently downgraded Device 1's progress" bug. Matches
        // [com.riffle.core.data.ReadingSessionRepositoryImpl.runSyncCycle]'s guard: for CLEAN
        // rows we ONLY pull (when server has advanced since our last sync), never push.
        val localDirty = snap.localUpdatedAt > snap.lastSyncedAt
        val serverAdvanced = read.lastUpdate > snap.lastSyncedAt

        return when {
            // ServerWins: (a) a clean row and the server has moved since our last sync — adopt it;
            // (b) a dirty row where the server stamp is strictly newer than our local edit — the
            // remote change beat our un-pushed edit and wins the last-update-wins race.
            (!localDirty && serverAdvanced) ||
                (localDirty && read.lastUpdate > snap.localUpdatedAt) -> {
                val applied = store.acceptServerPosition(
                    sourceId, itemId, read.position, serverStamp = read.lastUpdate,
                    ifLocalUpdatedAt = snap.localUpdatedAt,
                )
                if (applied) {
                    // Mirror the fresh server state into the UI-facing columns so the library
                    // grid / detail view re-emit; a Superseded (local edit raced in) skips this,
                    // since the local edit is now authoritative for both position and fraction.
                    uiSink.apply(sourceId, itemId, read.readingProgress, read.finishedAt)
                    ReconcileOutcome.ServerWon(read.position, read.lastUpdate)
                } else ReconcileOutcome.Superseded
            }

            // LocalWins: only when the row is DIRTY (there's a real pending edit to push) AND
            // the local stamp is strictly newer. Clean rows never enter this branch — they have
            // nothing new to push and the clock-skew guard above depends on it.
            localDirty && snap.localUpdatedAt > read.lastUpdate -> {
                val position = snap.position ?: return ReconcileOutcome.InSync
                val stamp = remote.patch(position) ?: return ReconcileOutcome.PushFailed
                val applied = store.confirmPushed(
                    sourceId, itemId, serverStamp = stamp, ifLocalUpdatedAt = snap.localUpdatedAt,
                )
                if (applied) ReconcileOutcome.LocalPushed(stamp) else ReconcileOutcome.Superseded
            }

            // Nothing to do: clean row and server hasn't moved (in sync), or dirty row with
            // stamps equal to the server's (an idempotent re-sync). Clear the dirty marker so
            // the sweep doesn't re-pick it.
            else -> {
                store.confirmInSync(sourceId, itemId, ifLocalUpdatedAt = snap.localUpdatedAt)
                ReconcileOutcome.InSync
            }
        }
    }
}
