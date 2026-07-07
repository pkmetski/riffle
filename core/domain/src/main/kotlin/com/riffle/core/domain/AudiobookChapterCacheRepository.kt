package com.riffle.core.domain

interface AudiobookChapterCacheRepository {
    suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>?
    suspend fun fetchAndCacheChapters(
        sourceId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter>
}
