package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.common.isDerivedCacheStale
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository

class PublicationMetricsRepositoryImpl constructor(
    private val dao: PublicationMetricsCacheDao,
    private val clock: Clock,
    private val libraryItemDao: LibraryItemDao,
    applicationScope: ApplicationScope,
) : PublicationMetricsRepository {

    init {
        applicationScope.launchSurvivable { libraryItemDao.backfillPageCountFromMetricsCache() }
    }

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
        // Write a length proxy to library_items.pageCount so the library sort-by-length works
        // without a DAO JOIN. For PDFs/CBZs, pageCount is the real value. For EPUBs, totalPositions
        // is used as a proxy (it's proportional to reading time, just in a different unit). The
        // null guard ensures real page counts already in the row are never overwritten by an estimate.
        val lengthProxy = metrics.pageCount ?: metrics.totalPositions
        if (lengthProxy != null) {
            libraryItemDao.updatePageCountIfNull(sourceId, itemId, lengthProxy)
        }
    }
}
