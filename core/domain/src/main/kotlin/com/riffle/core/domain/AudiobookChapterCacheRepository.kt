package com.riffle.core.domain

interface AudiobookChapterCacheRepository {
    suspend fun getCachedChapters(serverId: String, itemId: String): List<AudiobookChapter>?
    suspend fun fetchAndCacheChapters(
        serverId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter>
}
