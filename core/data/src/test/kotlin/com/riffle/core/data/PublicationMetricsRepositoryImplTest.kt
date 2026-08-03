package com.riffle.core.data

import com.riffle.core.common.DERIVED_CACHE_TTL_MS
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicationMetricsRepositoryImplTest {

    private companion object {
        const val NOW_MS = 1_760_000_000_000L
    }

    private class FakeDao : PublicationMetricsCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PublicationMetricsCacheEntity>()

        override suspend fun get(
            sourceId: String,
            itemId: String,
        ): PublicationMetricsCacheEntity? = rows[sourceId to itemId]

        override suspend fun upsert(entity: PublicationMetricsCacheEntity) {
            rows[entity.sourceId to entity.itemId] = entity
        }
    }

    @Test
    fun `epub position count round trips with file identity`() = runTest {
        val dao = FakeDao()
        val repository = PublicationMetricsRepositoryImpl(dao, TestClock(NOW_MS))

        repository.save(
            "src",
            "epub",
            PublicationMetrics(ebookFileIno = "ino-1", totalPositions = 480),
        )

        assertEquals(
            PublicationMetrics(ebookFileIno = "ino-1", totalPositions = 480),
            repository.get("src", "epub"),
        )
        assertEquals(NOW_MS, dao.rows.getValue("src" to "epub").cachedAt)
    }

    @Test
    fun `pdf page count round trips`() = runTest {
        val repository = PublicationMetricsRepositoryImpl(FakeDao(), TestClock(NOW_MS))

        repository.save(
            "src",
            "pdf",
            PublicationMetrics(ebookFileIno = "ino-2", pageCount = 321),
        )

        assertEquals(321, repository.get("src", "pdf")?.pageCount)
    }

    @Test
    fun `stale derived metrics are ignored`() = runTest {
        val dao = FakeDao()
        dao.rows["src" to "epub"] = PublicationMetricsCacheEntity(
            sourceId = "src",
            itemId = "epub",
            ebookFileIno = "ino-1",
            totalPositions = 480,
            pageCount = null,
            cachedAt = NOW_MS - DERIVED_CACHE_TTL_MS,
        )
        val repository = PublicationMetricsRepositoryImpl(dao, TestClock(NOW_MS))

        assertNull(repository.get("src", "epub"))
    }
}
