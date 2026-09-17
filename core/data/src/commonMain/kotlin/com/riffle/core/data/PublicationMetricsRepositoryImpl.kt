package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.common.isDerivedCacheStale
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository

class PublicationMetricsRepositoryImpl constructor(
    private val dao: PublicationMetricsCacheDao,
    private val clock: Clock,
) : PublicationMetricsRepository {

    override suspend fun get(sourceId: String, itemId: String): PublicationMetrics? {
        val entity = dao.get(sourceId, itemId) ?: return null
        if (isDerivedCacheStale(clock.nowMs(), entity.cachedAt)) return null
        return PublicationMetrics(
            ebookFileIno = entity.ebookFileIno,
            totalPositions = entity.totalPositions,
            pageCount = entity.pageCount,
            epubVersion = entity.epubVersion,
        )
    }

    override suspend fun save(
        sourceId: String,
        itemId: String,
        metrics: PublicationMetrics,
    ) {
        dao.upsert(
            PublicationMetricsCacheEntity(
                sourceId = sourceId,
                itemId = itemId,
                ebookFileIno = metrics.ebookFileIno,
                totalPositions = metrics.totalPositions,
                pageCount = metrics.pageCount,
                epubVersion = metrics.epubVersion,
                cachedAt = clock.nowMs(),
            )
        )
    }
}
