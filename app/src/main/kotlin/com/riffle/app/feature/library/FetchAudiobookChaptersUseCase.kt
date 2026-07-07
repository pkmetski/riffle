package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import javax.inject.Inject

class FetchAudiobookChaptersUseCase @Inject constructor(
    private val chapterCacheRepository: AudiobookChapterCacheRepository,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) {
    suspend operator fun invoke(item: LibraryItem): List<AudiobookChapter> {
        val cached = chapterCacheRepository.getCachedChapters(item.sourceId, item.id)
        if (cached != null) return cached

        val server = sourceRepository.getById(item.sourceId) ?: return emptyList()
        val token = tokenStorage.getToken(item.sourceId) ?: return emptyList()

        val baseUrl = server.url.value

        return try {
            chapterCacheRepository.fetchAndCacheChapters(
                sourceId = item.sourceId,
                itemId = item.id,
                baseUrl = baseUrl,
                token = token,
                insecureAllowed = server.insecureConnectionAllowed,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}
