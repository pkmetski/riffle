package com.riffle.core.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A reader position expressed in the canonical coordinate system of the cycle — the
 * Readium Locator on the EPUB the reader currently displays (ADR 0019). Opaque to the
 * cycle, which only compares update times and routes the value to remotes; remotes
 * translate it to/from their native coordinates at their boundary.
 */
data class CanonicalReaderPosition(val value: String)

/** The local reader position and the single `localUpdatedAt` it was last changed at. */
data class LocalCanonical(val position: CanonicalReaderPosition, val lastUpdate: Long)

/** A remote's position already translated to canonical, plus the time it last changed there. */
data class RemoteRead(val canonical: CanonicalReaderPosition, val lastUpdate: Long)

/**
 * One reconcilable position holder (ABS ebook, ABS audiobook, or Storyteller). Both
 * operations are per-target isolated: [tryGet] returns `null` when the remote is
 * unreachable or its position cannot be translated to canonical (so it is left out of the
 * inbound comparison and not patched), and [tryPatch] returns `false` on failure without
 * affecting any other remote.
 */
interface SyncRemote {
    val id: String
    suspend fun tryGet(): RemoteRead?
    suspend fun tryPatch(canonical: CanonicalReaderPosition): Boolean
}

/** Outcome of one reconciliation cycle. */
data class ThreePeerCycleResult(
    val jumpTo: CanonicalReaderPosition?,
    val patched: Set<String>,
    val canonicalLastUpdate: Long,
)

/**
 * The unified-canonical reconciliation cycle of ADR 0019, run over whatever remote set is
 * applicable to the open book. Invariant: one inbound winner, at most one reader jump, and
 * every outbound PATCH derived from the same canonical position.
 */
object ThreePeerSyncCycle {

    suspend fun run(local: LocalCanonical, remotes: List<SyncRemote>): ThreePeerCycleResult {
        // GET each remote in parallel, isolating per-target failures (null = excluded).
        val reads = coroutineScope {
            remotes.map { remote -> remote to async { remote.tryGet() } }
                .map { (remote, deferred) -> remote to deferred.await() }
        }

        // Inbound winner: the maximum lastUpdate among the local position and the
        // successfully-read remotes. Local wins ties, so an in-sync remote never forces a jump.
        var winnerCanonical = local.position
        var canonicalLastUpdate = local.lastUpdate
        for ((_, read) in reads) {
            if (read != null && read.lastUpdate > canonicalLastUpdate) {
                winnerCanonical = read.canonical
                canonicalLastUpdate = read.lastUpdate
            }
        }

        val jumpTo = if (winnerCanonical != local.position) winnerCanonical else null

        // Outbound: patch every successfully-read remote that is now stale, all from the
        // single canonical winner. Unreachable remotes (null read) are left untouched.
        val patched = mutableSetOf<String>()
        for ((remote, read) in reads) {
            if (read != null && read.lastUpdate < canonicalLastUpdate) {
                if (remote.tryPatch(winnerCanonical)) patched += remote.id
            }
        }

        return ThreePeerCycleResult(
            jumpTo = jumpTo,
            patched = patched,
            canonicalLastUpdate = canonicalLastUpdate,
        )
    }
}
