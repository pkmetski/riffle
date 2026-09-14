package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.common.Clock
import com.riffle.core.common.isDerivedCacheStale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AudiobookChapterCacheRepositoryImpl constructor(
    private val dao: AudiobookChapterCacheDao,
    private val catalogRegistry: CatalogRegistry,
    private val clock: Clock,
) : AudiobookChapterCacheRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // Bump this suffix whenever chapter-title generation logic changes so that stale cache
    // entries (keyed on the previous suffix) are transparently bypassed and re-fetched.
    // v1 = added show-name prefix stripping for Radio.es episode titles.
    // v2 = made show-name prefix match case-insensitive (fixes "El Espacio del Espacio").
    private fun cacheKey(itemId: String) = "$itemId/v2"

    override suspend fun getCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, cacheKey(itemId)) ?: return null
        if (isDerivedCacheStale(clock.nowMs(), entity.cachedAt)) return null
        return decode(entity)
    }

    override suspend fun getStaleCachedChapters(sourceId: String, itemId: String): List<AudiobookChapter>? {
        val entity = dao.get(sourceId, cacheKey(itemId)) ?: return null
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
                itemId = cacheKey(itemId),
                chaptersJson = json.encodeToString(chapters),
                cachedAt = clock.nowMs(),
            )
        )
        return chapters
    }

    private fun decode(entity: AudiobookChapterCacheEntity): List<AudiobookChapter> =
        json.decodeFromString(entity.chaptersJson)
}
