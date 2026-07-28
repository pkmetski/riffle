package com.riffle.core.common

/**
 * Shared TTL for derived-data caches (TOC, audiobook chapters). Chapter and TOC data
 * change rarely, so a week is comfortable for the steady state; the ceiling exists to
 * give a correctness fix in the underlying derivation (Readium TOC parser, Gramofonche
 * chapter probes, etc.) a bounded ride-along — the worst case for a user who hit a bad
 * result is a one-week wait before the next open reruns the live extraction.
 *
 * Kept as a single constant so a future tune (jitter, incident-driven bump) applies to
 * every derived cache at once. Pre-migration rows with `cachedAt = 0` are treated as
 * maximally stale on first read regardless of TTL length.
 */
const val DERIVED_CACHE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

/**
 * Returns true when a row with the given [cachedAt] wall-clock stamp should be treated
 * as stale under [DERIVED_CACHE_TTL_MS] given the current time [nowMs]. Also invalidates
 * on a negative diff (backward clock jump — device time set manually or NTP glitch)
 * so a rolled-back clock can't pin a bad row alive indefinitely.
 */
fun isDerivedCacheStale(nowMs: Long, cachedAt: Long): Boolean {
    val ageMs = nowMs - cachedAt
    return ageMs < 0 || ageMs >= DERIVED_CACHE_TTL_MS
}
