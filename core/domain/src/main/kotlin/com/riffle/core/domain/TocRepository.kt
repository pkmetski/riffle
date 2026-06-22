package com.riffle.core.domain

interface TocRepository {
    suspend fun getCachedToc(serverId: String, itemId: String): Pair<String, List<TocEntry>>?
    suspend fun saveToc(serverId: String, itemId: String, ebookFileIno: String, entries: List<TocEntry>)
}
