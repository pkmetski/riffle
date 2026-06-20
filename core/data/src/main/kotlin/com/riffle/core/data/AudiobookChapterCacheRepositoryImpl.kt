package com.riffle.core.data

import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.network.AbsItemDetailResult
import com.riffle.core.network.AbsLibraryApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class AudiobookChapterCacheRepositoryImpl @Inject constructor(
    private val dao: AudiobookChapterCacheDao,
    private val api: AbsLibraryApi,
) : AudiobookChapterCacheRepository {

    override suspend fun getCachedChapters(serverId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(serverId, itemId) ?: return null
        return Json.decodeFromString<List<AudiobookChapter>>(entity.chaptersJson)
    }

    override suspend fun fetchAndCacheChapters(
        serverId: String,
        itemId: String,
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): List<AudiobookChapter> {
        val result = api.getItemDetail(baseUrl, itemId, token, insecureAllowed)
        val response = when (result) {
            is AbsItemDetailResult.Success -> result.detail
            is AbsItemDetailResult.NetworkError -> return emptyList()
        }
        val chapters = response.media.chapters.mapIndexed { index, dto ->
            AudiobookChapter(
                index = index,
                startSec = dto.startSec,
                endSec = dto.endSec,
                title = dto.title,
            )
        }
        dao.upsert(AudiobookChapterCacheEntity(serverId, itemId, Json.encodeToString(chapters)))
        return chapters
    }
}
