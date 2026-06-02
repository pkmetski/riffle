package com.riffle.core.domain

/** A cached Readaloud audio bundle eligible for LRU eviction. */
data class CachedBundle(
    val key: String,
    val sizeBytes: Long,
    val lastAccessedAtMillis: Long,
)

/**
 * Pure LRU policy for the per-Storyteller-Server audio cache cap. Given the cached bundles and a
 * cap, returns the keys to evict — least-recently-accessed first — so the remaining total fits
 * under the cap. Permanent Downloads are never passed in, so they are never evicted (ADR 0023).
 */
object LruCacheEvictor {

    fun selectForEviction(bundles: List<CachedBundle>, capBytes: Long): List<String> {
        var total = bundles.sumOf { it.sizeBytes }
        if (total <= capBytes) return emptyList()

        val evicted = ArrayList<String>()
        for (bundle in bundles.sortedBy { it.lastAccessedAtMillis }) {
            if (total <= capBytes) break
            evicted += bundle.key
            total -= bundle.sizeBytes
        }
        return evicted
    }
}
