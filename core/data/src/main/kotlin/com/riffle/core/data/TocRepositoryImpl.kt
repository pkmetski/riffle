package com.riffle.core.data

import com.riffle.core.database.TocCacheDao
import com.riffle.core.database.TocCacheEntity
import com.riffle.core.common.Clock
import com.riffle.core.models.TocEntry
import com.riffle.core.domain.TocRepository
import com.riffle.core.common.isDerivedCacheStale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class TocRepositoryImpl @Inject constructor(
    private val dao: TocCacheDao,
    private val clock: Clock,
) : TocRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getCachedToc(sourceId: String, itemId: String): Pair<String, List<TocEntry>>? {
        val entity = dao.get(sourceId, itemId) ?: return null
        if (isDerivedCacheStale(clock.nowMs(), entity.cachedAt)) return null
        val entries = json.decodeFromString<List<TocEntry>>(entity.entriesJson)
        return entity.ebookFileIno to entries
    }

    override suspend fun saveToc(
        sourceId: String,
        itemId: String,
        ebookFileIno: String,
        entries: List<TocEntry>,
    ) {
        dao.upsert(
            TocCacheEntity(
                sourceId = sourceId,
                itemId = itemId,
                ebookFileIno = ebookFileIno,
                entriesJson = json.encodeToString(entries),
                cachedAt = clock.nowMs(),
            )
        )
    }
}
