package com.riffle.core.data

import com.riffle.core.database.TocCacheDao
import com.riffle.core.database.TocCacheEntity
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TocRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class TocRepositoryImpl @Inject constructor(
    private val dao: TocCacheDao,
) : TocRepository {

    override suspend fun getCachedToc(serverId: String, itemId: String): Pair<String, List<TocEntry>>? {
        val entity = dao.get(serverId, itemId) ?: return null
        val entries = Json.decodeFromString<List<TocEntry>>(entity.entriesJson)
        return entity.ebookFileIno to entries
    }

    override suspend fun saveToc(
        serverId: String,
        itemId: String,
        ebookFileIno: String,
        entries: List<TocEntry>,
    ) {
        dao.upsert(TocCacheEntity(serverId, itemId, ebookFileIno, Json.encodeToString(entries)))
    }
}
