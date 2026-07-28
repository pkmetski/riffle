package com.riffle.core.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A reader position expressed in the canonical coordinate system of the cycle — the
 * Readium Locator on the EPUB the reader currently displays (ADR 0019). Opaque to the
 * cycle, which only compares update times and routes the value to remotes; remotes
 * translate it to/from their native coordinates at their boundary.
 *
 * The wire form is the Locator JSON string; [href], [chapterProgression], and
 * [totalProgression] are lazily-parsed read accessors so callers can pull fields
 * without re-parsing the string at every site.
 */
data class CanonicalReaderPosition(val value: String) {
    private val parsed: ParsedLocator? by lazy(LazyThreadSafetyMode.NONE) { ParsedLocator.parse(value) }

    /** Spine href the locator points at, or `null` when the value isn't a parseable Locator JSON. */
    val href: String? get() = parsed?.href

    /** Within-chapter progression `[0..1]`, or `null` when absent / unparseable. */
    val chapterProgression: Double? get() = parsed?.chapterProgression

    /** Book-wide progression `[0..1]`. `null` for a canonical reconstructed from a remote
     *  (audio / Storyteller) — the bridge then weights it from chapter character counts. */
    val totalProgression: Double? get() = parsed?.totalProgression

    private class ParsedLocator(val href: String?, val chapterProgression: Double?, val totalProgression: Double?) {
        companion object {
            fun parse(value: String): ParsedLocator? {
                if (value.isEmpty()) return null
                val element = runCatching { Json.parseToJsonElement(value) }.getOrNull() ?: return null
                val root = element as? JsonObject ?: return null
                val href = (root["href"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                val locations = root["locations"] as? JsonObject
                val cp = (locations?.get("progression") as? JsonPrimitive)?.doubleOrNull
                val tp = (locations?.get("totalProgression") as? JsonPrimitive)?.doubleOrNull
                return ParsedLocator(href, cp, tp)
            }
        }
    }
}

/** The local reader position and the single `localUpdatedAt` it was last changed at. */
data class LocalCanonical(val position: CanonicalReaderPosition, val lastUpdate: Long)

/** A remote's position already translated to canonical, plus the time it last changed there. */
data class RemoteRead(val canonical: CanonicalReaderPosition, val lastUpdate: Long)

/**
 * The outcome of one [ProgressPeer.tryPatch] call. Explicit about the three things the cycle needs
 * to distinguish:
 *
 *  - [Ok] — the peer stored the write and stamped it server-side at [serverStamp]. The cycle adds
 *    the peer to `patched` and folds the stamp into the canonical `lastUpdate`, so the write
 *    doesn't read back next cycle as a "newer remote" (ADR 0008).
 *  - [Skipped] — the peer deliberately did not write (a translator boundary couldn't place the
 *    canonical position, an inbound-only wrapper suppressed the outbound, etc.). Not a failure;
 *    the row stays at its current state and the cycle does not retry.
 *  - [Failed] — the write was attempted and failed (network, server error). The cycle leaves the
 *    row dirty so a later sweep can retry; [retriable] is advisory for telemetry.
 *
 * The two non-Ok cases are observably identical in the live cycle (neither stamps, neither marks
 * the peer patched), but the distinction makes the contract honest and gives the sweep / telemetry
 * something to act on without sniffing nulls.
 */
sealed interface WriteResult {
    data class Ok(val serverStamp: Long) : WriteResult
    data object Skipped : WriteResult
    data class Failed(val reason: String, val retriable: Boolean = true) : WriteResult
}

/**
 * One reconcilable position holder for the live canonical cycle — today an ABS ebook peer and an
 * ABS audiobook peer (ADR 0019, as amended by ADR 0029 which dropped the Storyteller position
 * peer). Both operations are per-target isolated:
 *
 *  - [tryGet] returns `null` when the peer is unreachable or its position cannot be translated to
 *    canonical (so it is left out of the inbound comparison and not patched).
 *
 *  - [tryPatch] returns a [WriteResult]: [WriteResult.Ok] with the server timestamp the write was
 *    stored under, [WriteResult.Skipped] for a deliberate no-op (translator boundary, inbound-only
 *    wrapper), or [WriteResult.Failed] when the write was attempted and failed. The cycle adopts
 *    the [WriteResult.Ok.serverStamp] into the canonical `lastUpdate`; a peer that stamps later
 *    than the local clock (ABS stamps server-side) would otherwise read back next cycle as a
 *    "newer" position and bounce the reader to the very position it just wrote. A peer that stores
 *    the timestamp we sent simply returns it.
 *
 * Note: the local Room store is **not** a `ProgressPeer` — it is the canonical authority the cycle
 * is reconciling toward, and its write semantics are compare-and-swap (`ifLocalUpdatedAt` guarded
 * via [SyncPositionStore]) rather than push-then-stamp. Storyteller is also not a peer: there is
 * no progress-write endpoint on the Storyteller service. Both are intentionally absent; do not add
 * a no-op peer to satisfy a symmetry that doesn't exist.
 */
interface ProgressPeer {
    /** Stable identifier for logging, telemetry, and `SyncCycleResult.patched`. */
    val id: String
    suspend fun tryGet(): RemoteRead?
    suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult
}

/** Outcome of one reconciliation cycle. */
data class SyncCycleResult(
    val jumpTo: CanonicalReaderPosition?,
    val patched: Set<String>,
    val canonicalLastUpdate: Long,
)

/**
 * The unified-canonical reconciliation cycle of ADR 0019, run over whatever remote set is
 * applicable to the open book. Invariant: one inbound winner, at most one reader jump, and
 * every outbound PATCH derived from the same canonical position.
 */
object CanonicalSyncCycle {

    suspend fun run(local: LocalCanonical, peers: List<ProgressPeer>): SyncCycleResult {
        // GET each peer in parallel, isolating per-target failures (null = excluded).
        val reads = coroutineScope {
            peers.map { peer -> peer to async { peer.tryGet() } }
                .map { (peer, deferred) -> peer to deferred.await() }
        }

        // Inbound winner: the maximum lastUpdate among the local position and the
        // successfully-read peers. Local wins ties, so an in-sync peer never forces a jump.
        var winnerCanonical = local.position
        var canonicalLastUpdate = local.lastUpdate
        for ((_, read) in reads) {
            if (read != null && read.lastUpdate > canonicalLastUpdate) {
                winnerCanonical = read.canonical
                canonicalLastUpdate = read.lastUpdate
            }
        }

        val jumpTo = if (winnerCanonical != local.position) winnerCanonical else null

        // Outbound: patch every successfully-read peer that is now stale, all from the
        // single canonical winner. Unreachable peers (null read) are left untouched. Adopt the
        // latest server timestamp any write was stored under, so a write the server stamps later
        // than the local clock doesn't read back next cycle as a "newer" peer and bounce the
        // reader to the position it just wrote (the "server always overwrites local" bug).
        val patched = mutableSetOf<String>()
        var effectiveLastUpdate = canonicalLastUpdate
        for ((peer, read) in reads) {
            if (read != null && read.lastUpdate < canonicalLastUpdate) {
                when (val outcome = peer.tryPatch(winnerCanonical)) {
                    is WriteResult.Ok -> {
                        patched += peer.id
                        if (outcome.serverStamp > effectiveLastUpdate) effectiveLastUpdate = outcome.serverStamp
                    }
                    // Skipped/Failed: leave the peer out of `patched`; the cycle's other peers and
                    // the durable sweep (ADR 0030) handle retry.
                    WriteResult.Skipped, is WriteResult.Failed -> Unit
                }
            }
        }

        return SyncCycleResult(
            jumpTo = jumpTo,
            patched = patched,
            canonicalLastUpdate = effectiveLastUpdate,
        )
    }
}
