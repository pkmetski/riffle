package com.riffle.core.domain

interface AudiobookChapterCacheRepository {
    /**
     * Returns the cached chapters when the row is present and fresh under
     * [DERIVED_CACHE_TTL_MS]. Returns null when the row is missing OR stale; callers
     * that want the stale-safe fallback (e.g. offline flows where a live fetch just
     * failed) should use [getStaleCachedChapters] as a last resort.
     */
    suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>?

    /**
     * Returns whatever is in the cache regardless of freshness — or null if there's no
     * row at all. The stale-safe fallback for offline flows where a live fetch has
     * already failed: a week-old cache is still better than an empty chapter list, and
     * losing it would defeat the whole purpose of caching.
     */
    suspend fun getStaleCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>?

    /**
     * Fetch chapter markers for [itemId] from the Source identified by [sourceId] and upsert them
     * into the local cache. Returns the fetched chapters (possibly empty on a network error).
     */
    suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter>
}
