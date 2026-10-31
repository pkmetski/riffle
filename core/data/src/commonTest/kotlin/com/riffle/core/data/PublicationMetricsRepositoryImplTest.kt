package com.riffle.core.data

import com.riffle.core.common.DERIVED_CACHE_TTL_MS
import com.riffle.core.data.testing.TestApplicationScope
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.PublicationMetricsCacheDao
import com.riffle.core.database.PublicationMetricsCacheEntity
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.TestClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun makeRepo(
        dao: FakeDao = FakeDao(),
        libraryItemDao: FakeLibraryItemDao = FakeLibraryItemDao(),
    ) = PublicationMetricsRepositoryImpl(dao, TestClock(NOW_MS), libraryItemDao, TestApplicationScope())

    @Test
    fun `epub position count round trips with file identity`() = runTest {
        val dao = FakeDao()
        val repository = makeRepo(dao)

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
        val repository = makeRepo()

        repository.save(
            "src",
            "pdf",
            PublicationMetrics(ebookFileIno = "ino-2", pageCount = 321),
        )

        assertEquals(321, repository.get("src", "pdf")?.pageCount)
    }

    @Test
    fun `epub version round trips`() = runTest {
        val repository = makeRepo()

        repository.save(
            "src",
            "epub",
            PublicationMetrics(ebookFileIno = "ino-1", epubVersion = "3.0"),
        )

        assertEquals("3.0", repository.get("src", "epub")?.epubVersion)
    }

    @Test
    fun `null epub version round trips`() = runTest {
        val repository = makeRepo()

        repository.save(
            "src",
            "epub",
            PublicationMetrics(ebookFileIno = "ino-1", epubVersion = null),
        )

        assertNull(repository.get("src", "epub")?.epubVersion)
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
        val repository = makeRepo(dao)

        assertNull(repository.get("src", "epub"))
    }

    private fun libraryItemEntity(
        sourceId: String,
        itemId: String,
        libraryId: String,
        pageCount: Int?,
    ) = LibraryItemEntity(
        sourceId = sourceId, id = itemId, libraryId = libraryId,
        title = "Book", author = "Author", coverUrl = null,
        readingProgress = 0f, ebookFormat = "epub", hasAudio = false,
        audioDurationSec = 0.0, addedAt = 0L, pageCount = pageCount,
    )

    @Test
    fun `saving page count writes through to library item when null`() = runTest {
        val libraryItemDao = FakeLibraryItemDao()
        libraryItemDao.emit("src", listOf(libraryItemEntity(sourceId = "src", itemId = "pdf-1", libraryId = "books", pageCount = null)))
        val repository = makeRepo(libraryItemDao = libraryItemDao)

        repository.save("src", "pdf-1", PublicationMetrics(ebookFileIno = "ino-1", pageCount = 287))

        assertEquals(287, libraryItemDao.itemsFor("books").first { it.id == "pdf-1" }.pageCount)
    }

    @Test
    fun `saving total positions writes through to library item as page count proxy when null`() = runTest {
        val libraryItemDao = FakeLibraryItemDao()
        libraryItemDao.emit("src", listOf(libraryItemEntity(sourceId = "src", itemId = "epub-1", libraryId = "books", pageCount = null)))
        val repository = makeRepo(libraryItemDao = libraryItemDao)

        repository.save("src", "epub-1", PublicationMetrics(ebookFileIno = "ino-1", totalPositions = 8400))

        assertEquals(8400, libraryItemDao.itemsFor("books").first { it.id == "epub-1" }.pageCount)
    }

    @Test
    fun `saving page count does not overwrite existing page count`() = runTest {
        val libraryItemDao = FakeLibraryItemDao()
        libraryItemDao.emit("src", listOf(libraryItemEntity(sourceId = "src", itemId = "cbz-1", libraryId = "books", pageCount = 42)))
        val repository = makeRepo(libraryItemDao = libraryItemDao)

        repository.save("src", "cbz-1", PublicationMetrics(ebookFileIno = "ino-2", pageCount = 99))

        assertEquals(42, libraryItemDao.itemsFor("books").first { it.id == "cbz-1" }.pageCount)
    }

    @Test
    fun `library refresh with null server pageCount preserves locally derived pageCount`() = runTest {
        val libraryItemDao = FakeLibraryItemDao()
        libraryItemDao.emit("src", listOf(libraryItemEntity(sourceId = "src", itemId = "epub-1", libraryId = "books", pageCount = 8400)))

        libraryItemDao.updateMetadata(
            sourceId = "src", id = "epub-1", libraryId = "books",
            title = "Book", author = "Author", coverUrl = null,
            ebookFileIno = null, ebookFormat = "epub",
            hasAudio = false, audioDurationSec = 0.0,
            description = null, seriesName = null, publishedYear = null,
            genres = "", publisher = null, language = null,
            lastOpenedAt = null, addedAt = 0L,
            isbn = null, asin = null, finishedAt = null,
            pageCount = null,
        )

        assertEquals(8400, libraryItemDao.itemsFor("books").first { it.id == "epub-1" }.pageCount)
    }
}
