package com.riffle.core.data

import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class AudiobookChapterCacheRepositoryImpl @Inject constructor(
    private val dao: AudiobookChapterCacheDao,
    private val api: AbsLibraryApi,
) : AudiobookChapterCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, itemId) ?: return null
        return json.decodeFromString<List<AudiobookChapter>>(entity.chaptersJson)
    }

    override suspend fun fetchAndCacheChapters(
        sourceId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter> {
        val result = api.getItemDetail(baseUrl, itemId, token, insecureAllowed)
        val response = (result as? NetworkResult.Success)?.value ?: return emptyList()
        val chapters = response.media.chapters.mapIndexed { index, dto ->
            AudiobookChapter(
                index = index,
                startSec = dto.startSec,
                endSec = dto.endSec,
                title = dto.title,
            )
        }
        dao.upsert(AudiobookChapterCacheEntity(sourceId, itemId, json.encodeToString(chapters)))
        return chapters
    }
}
