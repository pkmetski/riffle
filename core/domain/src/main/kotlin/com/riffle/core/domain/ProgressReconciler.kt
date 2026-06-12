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
 * The durable single-target reconcile primitive of ADR 0030: GET-before-PATCH last-update-wins for
 * one ABS record, with compare-and-clear so a concurrent offline edit is never overwritten or lost.
 * Pure orchestration over a [SyncPositionStore] and a per-row [ProgressRemote] — no Android, Room, or
 * network. Used directly by the headless sweep worker (single target) and composed by the live
 * multi-peer cycle.
 */
class ProgressReconciler<P>(private val store: SyncPositionStore<P>) {

    suspend fun reconcile(serverId: String, itemId: String, remote: ProgressRemote<P>): ReconcileOutcome<P> {
        val snap = store.snapshot(serverId, itemId)
        val read = remote.get() ?: return ReconcileOutcome.Offline

        return when {
            // Server is strictly newer — pull. (Local wins ties, so an in-sync remote never pulls.)
            read.lastUpdate > snap.localUpdatedAt -> {
                val applied = store.acceptServerPosition(
                    serverId, itemId, read.position, serverStamp = read.lastUpdate,
                    ifLocalUpdatedAt = snap.localUpdatedAt,
                )
                if (applied) ReconcileOutcome.ServerWon(read.position, read.lastUpdate)
                else ReconcileOutcome.Superseded
            }

            // Local is strictly newer — push, then adopt the server-returned stamp.
            snap.localUpdatedAt > read.lastUpdate -> {
                val position = snap.position ?: return ReconcileOutcome.InSync
                val stamp = remote.patch(position) ?: return ReconcileOutcome.PushFailed
                val applied = store.confirmPushed(
                    serverId, itemId, serverStamp = stamp, ifLocalUpdatedAt = snap.localUpdatedAt,
                )
                if (applied) ReconcileOutcome.LocalPushed(stamp) else ReconcileOutcome.Superseded
            }

            // Equal — already in sync; clear any lingering dirty marker.
            else -> {
                store.confirmInSync(serverId, itemId, ifLocalUpdatedAt = snap.localUpdatedAt)
                ReconcileOutcome.InSync
            }
        }
    }
}
