package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.models.LibraryItem
import javax.inject.Inject

class FetchAudiobookChaptersUseCase @Inject constructor(
    private val chapterCacheRepository: AudiobookChapterCacheRepository,
) {
    suspend operator fun invoke(item: LibraryItem): List<AudiobookChapter> {
        // 1. Fresh cache — return immediately.
        val fresh = chapterCacheRepository.getCachedChapters(item.sourceId, item.id)
        if (fresh != null) return fresh
        // 2. Live fetch — the TTL refresh path.
        val fetched = try {
            chapterCacheRepository.fetchAndCacheChapters(sourceId = item.sourceId, itemId = item.id)
        } catch (_: Exception) {
            emptyList()
        }
        if (fetched.isNotEmpty()) return fetched
        // 3. Live fetch failed or came back empty (offline, rate-limit, source hiccup) — a
        //    stale cached copy is still better than an empty chapter list, so surface it
        //    as a last resort. Otherwise the TTL turns a purely-additive safeguard into a
        //    regression on the offline path.
        return chapterCacheRepository.getStaleCachedChapters(item.sourceId, item.id).orEmpty()
    }
}
