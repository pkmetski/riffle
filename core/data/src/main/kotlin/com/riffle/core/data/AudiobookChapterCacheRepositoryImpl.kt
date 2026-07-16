package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.Clock
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
        if (clock.nowMs() - entity.cachedAt >= CACHE_TTL_MS) return null
        return json.decodeFromString<List<AudiobookChapter>>(entity.chaptersJson)
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

    companion object {
        /**
         * How long a cached chapter list is trusted before we re-fetch. Chapter data changes
         * rarely, so a week is comfortable for the steady state; the ceiling exists to give
         * correctness fixes in `getAudiobookChapters` a bounded ride-along — the worst case
         * for a user who hit a bad result is a one-week wait before the next open reruns the
         * live probe. Existing rows written before the TTL migration have `cachedAt = 0` and
         * are treated as maximally stale on the first read, so any fix rolled out with a bump
         * of this class heals on next open regardless of TTL length.
         */
        internal const val CACHE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
