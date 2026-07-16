package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.Clock
import com.riffle.core.domain.isDerivedCacheStale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class AudiobookChapterCacheRepositoryImpl @Inject constructor(
    private val dao: AudiobookChapterCacheDao,
    private val catalogRegistry: CatalogRegistry,
    private val clock: Clock,
) : AudiobookChapterCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, itemId) ?: return null
        if (isDerivedCacheStale(clock.nowMs(), entity.cachedAt)) return null
        return decode(entity)
    }

    override suspend fun getStaleCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, itemId) ?: return null
        return decode(entity)
    }

    override suspend fun fetchAndCacheChapters(sourceId: String, itemId: String): List<AudiobookChapter> {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return emptyList()
        val audioCap = catalog as? AudiobookMediaCapability ?: return emptyList()
        val chapters = runCatching { audioCap.getAudiobookChapters(itemId) }.getOrElse { return emptyList() }
            .map { c -> AudiobookChapter(index = c.index, startSec = c.startSec, endSec = c.endSec, title = c.title) }
        dao.upsert(
            AudiobookChapterCacheEntity(
                sourceId = sourceId,
                itemId = itemId,
                chaptersJson = json.encodeToString(chapters),
                cachedAt = clock.nowMs(),
            )
        )
        return chapters
    }

    private fun decode(entity: AudiobookChapterCacheEntity): List<AudiobookChapter> =
        json.decodeFromString(entity.chaptersJson)
}
