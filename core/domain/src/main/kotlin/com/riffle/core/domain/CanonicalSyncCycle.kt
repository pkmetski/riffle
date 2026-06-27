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
 * One reconcilable position holder (ABS ebook, ABS audiobook, or Storyteller). Both
 * operations are per-target isolated: [tryGet] returns `null` when the remote is
 * unreachable or its position cannot be translated to canonical (so it is left out of the
 * inbound comparison and not patched).
 *
 * [tryPatch] returns the **server timestamp the write was stored under** (or `null` on
 * failure / a deliberate no-op). The cycle folds that timestamp into the canonical
 * `lastUpdate`: a remote that stamps the write later than the local clock (ABS stamps
 * server-side) would otherwise read back next cycle as a "newer" position and bounce the
 * reader to the very position it just wrote. Adopting the returned timestamp keeps local
 * the winner on the next tie. A remote that stores the timestamp we sent simply returns it.
 */
interface SyncRemote {
    val id: String
    suspend fun tryGet(): RemoteRead?
    suspend fun tryPatch(canonical: CanonicalReaderPosition): Long?
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

    suspend fun run(local: LocalCanonical, remotes: List<SyncRemote>): SyncCycleResult {
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
        // single canonical winner. Unreachable remotes (null read) are left untouched. Adopt the
        // latest server timestamp any write was stored under, so a write the server stamps later
        // than the local clock doesn't read back next cycle as a "newer" remote and bounce the
        // reader to the position it just wrote (the "server always overwrites local" bug).
        val patched = mutableSetOf<String>()
        var effectiveLastUpdate = canonicalLastUpdate
        for ((remote, read) in reads) {
            if (read != null && read.lastUpdate < canonicalLastUpdate) {
                val stamp = remote.tryPatch(winnerCanonical)
                if (stamp != null) {
                    patched += remote.id
                    if (stamp > effectiveLastUpdate) effectiveLastUpdate = stamp
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
