package com.riffle.core.domain

/** A remote progress record for one target, already in the local payload's coordinate [P]. */
data class RemoteProgress<P>(val position: P, val lastUpdate: Long)

/**
 * The remote (ABS) side of one reconcilable target. Both operations are per-target isolated:
 *
 * - [get] returns `null` when the record is unreachable or cannot be read this cycle (offline).
 * - [patch] returns the **server timestamp the write was stored under** (or `null` on failure).
 *   ABS stamps server-side, so adopting the returned stamp keeps a freshly-pushed position from
 *   reading back next cycle as a "newer remote" (ADR 0008/0030).
 */
interface ProgressRemote<P> {
    suspend fun get(): RemoteProgress<P>?
    suspend fun patch(position: P): Long?
}
