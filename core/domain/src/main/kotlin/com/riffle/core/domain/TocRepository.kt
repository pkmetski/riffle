package com.riffle.core.domain
import com.riffle.core.models.TocEntry

interface TocRepository {
    suspend fun getCachedToc(sourceId: String, itemId: String): Pair<String, List<TocEntry>>?
    suspend fun saveToc(sourceId: String, itemId: String, ebookFileIno: String, entries: List<TocEntry>)
}
