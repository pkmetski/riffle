package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TestClock
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sync.DirtyProgressLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [LibraryRepositoryImpl] observer and refresher behaviour.
 *
 * Regression: iOS previously used three separate implementations
 * ([IosLibraryObserverImpl], [IosLibraryRefresherImpl], [IosLibraryMutatorImpl]) that omitted
 * two critical overrides:
 *  - `observeAllItemsForSource(sourceId)` fell back to `flowOf(emptyList())`, making the
 *    Chitanka/Gutenberg/radio.es "Unowned" filter always empty on iOS.
 *  - `observeItem(sourceId, itemId)` fell back to the active-source lookup instead of the
 *    source-scoped query, breaking item detail for non-active sources on iOS.
 *
 * Tests that assert these two behaviours must fail when re-introducing either default.
 */
class LibraryRepositoryImplObserverTest {

    // ── Fakes ────────────────────────────────────────────────────────────────────

    private class FixedSourceRepository(private val sources: List<Source>) : SourceRepository {
        constructor(vararg sources: Source) : this(sources.toList())
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(sources)
        override suspend fun getActive(): Source? = sources.firstOrNull { it.isActive }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class RecordingLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        val progressUpdates = mutableListOf<Triple<String, String, Float>>()
        val finishedAtUpdates = mutableListOf<Triple<String, String, Long?>>()
        private val rows = mutableMapOf<String, LibraryItemEntity>()

        fun seed(entity: LibraryItemEntity) {
            rows[entity.id] = entity
        }

        override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> =
            flowOf(rows.values.filter { it.sourceId == sourceId })

        override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> =
            flowOf(rows[itemId]?.takeIf { it.sourceId == sourceId })

        override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? =
            rows[itemId]?.takeIf { it.sourceId == sourceId }

        override fun observeInProgressAllSources(): Flow<List<LibraryItemEntity>> =
            flowOf(rows.values.filter { it.readingProgress > 0f }.toList())

        override fun observeAllSources(): Flow<List<LibraryItemEntity>> =
            flowOf(rows.values.toList())

        override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
            flowOf(rows.values.filter { it.sourceId == sourceId && it.libraryId == libraryId })

        override suspend fun updateReadingProgressFromServer(
            sourceId: String, itemId: String, progress: Float, serverUpdatedAt: Long,
        ) {
            val existing = rows[itemId]
            if (existing != null && serverUpdatedAt < existing.progressServerUpdatedAt) return
            progressUpdates += Triple(sourceId, itemId, progress)
            if (existing != null) rows[itemId] = existing.copy(readingProgress = progress, progressServerUpdatedAt = serverUpdatedAt)
        }

        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {
            finishedAtUpdates += Triple(sourceId, itemId, finishedAt)
        }
        override suspend fun backfillPageCountFromMetricsCache() = Unit
        override suspend fun updatePageCountIfNull(sourceId: String, itemId: String, pageCount: Int) = Unit

        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> = emptyList()
        override suspend fun replaceAllForLibrary(sourceId: String, libraryId: String, items: List<LibraryItemEntity>) = Unit
    }

    private class RecordingSeriesDao : SeriesDao by ThrowingSeriesDao {
        val queries = mutableListOf<Pair<String, String>>()
        private val seriesIds = mutableMapOf<Pair<String, String>, String?>()

        fun seed(sourceId: String, itemId: String, seriesId: String?) {
            seriesIds[sourceId to itemId] = seriesId
        }

        override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? {
            queries += sourceId to itemId
            return seriesIds[sourceId to itemId]
        }
    }

    private object NoopCollectionDao : CollectionDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = flowOf(emptyList())
        override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(collections: List<CollectionEntity>) = Unit
        override suspend fun upsertAllItems(items: List<CollectionItemEntity>) = Unit
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
    }

    private object NoopDirtyLedger : DirtyProgressLedger {
        override suspend fun serversWithDirty(): List<String> = emptyList()
        override suspend fun dirtyEbookItems(sourceId: String): List<String> = emptyList()
        override suspend fun dirtyAudioItems(sourceId: String): List<String> = emptyList()
    }

    private class FakeProgressPeer(private val progress: CatalogProgress) : Catalog, ProgressPeerCapability {
        override val sourceType: SourceType = SourceType.ABS
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = throw UnsupportedOperationException()
        override suspend fun connectivityCheck(): CatalogHealth = throw UnsupportedOperationException()
        override suspend fun pushEbookProgress(itemId: String, location: String, progress: Float, isFinished: Boolean?, lastUpdateEpochMs: Long): Long? = null
        override suspend fun pullProgress(itemId: String): CatalogProgress = progress
        override suspend fun pullAllProgress(): List<CatalogProgress> = listOf(progress)
    }

    private class FixedCatalogRegistry(private val catalog: Catalog) : CatalogRegistry {
        override suspend fun forActive(): Catalog = catalog
        override suspend fun forSource(source: Source): Catalog = catalog
        override suspend fun forSourceId(sourceId: String): Catalog = catalog
    }

    private fun absSource(id: String = "s1", isActive: Boolean = true) = Source(
        id = id,
        url = SourceUrl.parse("http://abs.test")!!,
        isActive = isActive,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
    )

    private fun item(sourceId: String, id: String, libraryId: String = "lib-1") = LibraryItemEntity(
        sourceId = sourceId, id = id, libraryId = libraryId,
        title = "T-$id", author = "A", coverUrl = null,
        readingProgress = 0f, ebookFormat = "epub", hasAudio = false,
        audioDurationSec = 0.0, addedAt = 0L,
    )

    private fun repo(
        sources: List<Source>,
        itemDao: LibraryItemDao = ThrowingLibraryItemDao,
        seriesDao: SeriesDao = ThrowingSeriesDao,
        catalog: Catalog = FakeProgressPeer(CatalogProgress(itemId = "x", lastUpdate = 0L)),
        ledger: DirtyProgressLedger = NoopDirtyLedger,
    ) = LibraryRepositoryImpl(
        catalogRegistry = FixedCatalogRegistry(catalog),
        libraryDao = ThrowingLibraryDao,
        libraryItemDao = itemDao,
        seriesDao = seriesDao,
        collectionDao = NoopCollectionDao,
        sourceRepository = FixedSourceRepository(sources),
        clock = TestClock(),
        logger = RecordingLogger(),
        dirtyProgressLedger = ledger,
    )

    // ── observeAllItemsForSource — regression: iOS always returned emptyList ──────

    @Test
    fun observeAllItemsForSourceReturnsItemsForThatSource() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "item-a"))
        dao.seed(item("src-1", "item-b"))
        dao.seed(item("src-2", "item-c"))

        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeAllItemsForSource("src-1").first()

        assertEquals(setOf("item-a", "item-b"), result.map { it.id }.toSet())
    }

    @Test
    fun observeAllItemsForSourceReturnsEmptyForUnknownSource() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "item-a"))

        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeAllItemsForSource("src-99").first()

        assertEquals(emptyList(), result)
    }

    // ── observeItem(sourceId, itemId) — regression: iOS resolved wrong source ────

    @Test
    fun observeItemWithSourceIdReturnsItemForThatSource() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "item-1"))
        dao.seed(item("src-2", "item-1"))

        // Active source is src-1 — the correct impl must still return src-2's item
        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeItem("src-2", "item-1").first()

        assertEquals("src-2", result?.sourceId)
    }

    @Test
    fun observeItemWithSourceIdIgnoresActiveSource() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-2", "item-1"))
        // item-1 does NOT exist for src-1 (the active source)

        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeItem("src-2", "item-1").first()

        assertEquals("src-2", result?.sourceId)
    }

    @Test
    fun observeItemWithSourceIdReturnsNullWhenItemAbsentFromThatSource() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "item-1"))

        // item-1 exists on src-1 but we ask for src-2 — should be null
        assertNull(
            repo(listOf(absSource("src-1")), itemDao = dao)
                .observeItem("src-2", "item-1").first()
        )
    }

    // ── observeInProgressItemsAllSources / observeAllLibraryItemsAllSources ─────

    @Test
    fun observeInProgressItemsAllSourcesDelegatesToDao() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "in-progress").copy(readingProgress = 0.4f))

        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeInProgressItemsAllSources().first()

        assertEquals(listOf("in-progress"), result.map { it.id })
    }

    @Test
    fun observeAllLibraryItemsAllSourcesDelegatesToDao() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("src-1", "item-all"))

        val result = repo(listOf(absSource("src-1")), itemDao = dao)
            .observeAllLibraryItemsAllSources().first()

        assertEquals(listOf("item-all"), result.map { it.id })
    }

    // ── getSeriesIdForItem — regression: was hardcoded null on iOS ───────────────

    @Test
    fun getSeriesIdForItemDelegatesToSeriesDao() = runTest {
        val seriesDao = RecordingSeriesDao()
        seriesDao.seed("src-1", "item-1", "series-9")

        val result = repo(listOf(absSource("src-1")), seriesDao = seriesDao)
            .getSeriesIdForItem("src-1", "item-1")

        assertEquals("series-9", result)
        assertEquals(listOf("src-1" to "item-1"), seriesDao.queries)
    }

    @Test
    fun getSeriesIdForItemReturnsNullForUnseriedItem() = runTest {
        val seriesDao = RecordingSeriesDao()
        seriesDao.seed("src-1", "loner", null)

        assertNull(
            repo(listOf(absSource("src-1")), seriesDao = seriesDao)
                .getSeriesIdForItem("src-1", "loner")
        )
    }

    // ── refreshItemProgress — mirrors IosLibraryRefresherImplTest ────────────────

    @Test
    fun refreshItemProgressWritesDerivedFractionToDao() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("s1", "item-1"))
        val peer = FakeProgressPeer(
            CatalogProgress(itemId = "item-1", ebookProgress = 0.6f, isFinished = false, lastUpdate = 5_000L)
        )

        repo(listOf(absSource("s1")), itemDao = dao, catalog = peer)
            .refreshItemProgress("s1", "item-1")

        assertEquals(1, dao.progressUpdates.size)
        assertEquals(Triple("s1", "item-1", 0.6f), dao.progressUpdates.first())
    }

    @Test
    fun refreshItemProgressSkipsWhenPositionRowIsDirty() = runTest {
        val dao = RecordingLibraryItemDao()
        dao.seed(item("s1", "item-1").copy(readingProgress = 0.75f))
        val peer = FakeProgressPeer(
            CatalogProgress(itemId = "item-1", ebookProgress = 0.42f, isFinished = false, lastUpdate = 5_000L)
        )
        val dirtyLedger = object : DirtyProgressLedger {
            override suspend fun serversWithDirty(): List<String> = listOf("s1")
            override suspend fun dirtyEbookItems(sourceId: String): List<String> = listOf("item-1")
            override suspend fun dirtyAudioItems(sourceId: String): List<String> = emptyList()
        }

        repo(listOf(absSource("s1")), itemDao = dao, catalog = peer, ledger = dirtyLedger)
            .refreshItemProgress("s1", "item-1")

        assertEquals(0, dao.progressUpdates.size)
    }

    @Test
    fun refreshItemProgressDoesNothingWhenNoActiveSource() = runTest {
        val dao = RecordingLibraryItemDao()
        val peer = FakeProgressPeer(
            CatalogProgress(itemId = "item-1", ebookProgress = 0.5f, isFinished = false, lastUpdate = 0L)
        )

        val result = repo(emptyList(), itemDao = dao, catalog = peer)
            .refreshItemProgress("s1", "item-1")

        assertEquals(LibraryRefreshResult.NoActiveServer, result)
        assertEquals(0, dao.progressUpdates.size)
    }
}

private object ThrowingSeriesDao : SeriesDao {
    override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> = throw UnsupportedOperationException()
    override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> = throw UnsupportedOperationException()
    override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeContinueSeriesAllSources(): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? = null
    override suspend fun upsertAll(series: List<SeriesEntity>) = Unit
    override suspend fun upsertAllItems(items: List<SeriesItemEntity>) = Unit
    override suspend fun deleteByLibraryId(libraryId: String) = Unit
    override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
}
