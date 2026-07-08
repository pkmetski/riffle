package com.riffle.core.domain

interface AudiobookChapterCacheRepository {
    suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>?

    /**
     * Fetch chapter markers for [itemId] from the Source identified by [sourceId] and upsert them
     * into the local cache. Returns the fetched chapters (possibly empty on a network error).
     */
    suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter>
}
