package com.riffle.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.models.LibraryItem

class FetchAudiobookChaptersUseCase constructor(
    private val chapterCacheRepository: AudiobookChapterCacheRepository,
) {
    suspend operator fun invoke(item: LibraryItem): List<AudiobookChapter> {
        val fresh = chapterCacheRepository.getCachedChapters(item.sourceId, item.id)
        if (fresh != null) return fresh
        val fetched = try {
            chapterCacheRepository.fetchAndCacheChapters(sourceId = item.sourceId, itemId = item.id)
        } catch (_: Exception) {
            emptyList()
        }
        if (fetched.isNotEmpty()) return fetched
        return chapterCacheRepository.getStaleCachedChapters(item.sourceId, item.id).orEmpty()
    }
}
