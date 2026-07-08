package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class AudiobookChapterCacheRepositoryImpl @Inject constructor(
    private val dao: AudiobookChapterCacheDao,
    private val catalogRegistry: CatalogRegistry,
) : AudiobookChapterCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, itemId) ?: return null
        return json.decodeFromString<List<AudiobookChapter>>(entity.chaptersJson)
    }

    override suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter> {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return emptyList()
        val audioCap = catalog as? AudiobookMediaCapability ?: return emptyList()
        val chapters = runCatching { audioCap.getAudiobookChapters(itemId) }.getOrElse { return emptyList() }
            .map { c -> AudiobookChapter(index = c.index, startSec = c.startSec, endSec = c.endSec, title = c.title) }
        dao.upsert(AudiobookChapterCacheEntity(sourceId, itemId, json.encodeToString(chapters)))
        return chapters
    }
}
