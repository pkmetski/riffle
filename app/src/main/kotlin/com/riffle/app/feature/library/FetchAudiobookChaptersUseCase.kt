package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.LibraryItem
import javax.inject.Inject

class FetchAudiobookChaptersUseCase @Inject constructor(
    private val chapterCacheRepository: AudiobookChapterCacheRepository,
) {
    suspend operator fun invoke(item: LibraryItem): List<AudiobookChapter> {
        val cached = chapterCacheRepository.getCachedChapters(item.sourceId, item.id)
        if (cached != null) return cached
        return try {
            chapterCacheRepository.fetchAndCacheChapters(sourceId = item.sourceId, itemId = item.id)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
