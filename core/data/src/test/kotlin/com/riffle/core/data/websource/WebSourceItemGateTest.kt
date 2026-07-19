package com.riffle.core.data.websource

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.models.SourceType
import com.riffle.core.database.RemoteItemFreshnessDao
import com.riffle.core.database.RemoteItemFreshnessEntity
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Series
import com.riffle.core.domain.TestClock
import com.riffle.core.logging.RecordingLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WebSourceItemGateTest {

    private val sourceId = "chi1"
    private val itemId = "text/44723-abu-hasan"

    private fun sampleItem(title: String = "Абу Хасан"): CatalogItem = CatalogItem(
        id = itemId,
        rootId = "books",
        title = title,
        author = "Автор",
        // Non-null cover: the gate treats a missing cover as an incomplete fetch and skips
        // stamping, which would break every test that asserts stamping. Cover-missing tests
        // opt in explicitly via `.copy(coverUrl = null)`.
        coverUrl = "https://chitanka.info/cover.jpg",
        ebookFormat = BookFormat.Epub,
    )

    private fun sampleLibraryItem(title: String = "Абу Хасан"): LibraryItem = LibraryItem(
        id = itemId,
        libraryId = "books",
        title = title,
        author = "Автор",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = sourceId,
    )

    private fun relaxedUpserter(): WebSourceLibraryItemUpserter =
        mockk<WebSourceLibraryItemUpserter>(relaxed = true)

    @Test fun `within TTL and row present returns Fresh without invoking catalog`() = runTest {
        val clock = TestClock(1_000L)
        val freshness = RemoteItemFreshness(dao = InMemoryFreshnessDao(), clock = clock)
        freshness.stamp(sourceId, itemId)

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val catalog = RecordingCatalog(item = sampleItem())
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        clock.advance(60_000L) // 1 minute later, well within TTL

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)
        assertEquals(WebSourceItemGate.Outcome.Fresh, outcome)
        assertEquals(0, catalog.getItemCalls)
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test fun `within TTL but no row falls through to fetch`() = runTest {
        val clock = TestClock(1_000L)
        val freshness = RemoteItemFreshness(InMemoryFreshnessDao(), clock)
        freshness.stamp(sourceId, itemId)

        val observer = FakeLibraryObserver(items = mutableMapOf()) // no row
        val fresh = sampleItem(title = "Fetched")
        val catalog = RecordingCatalog(item = fresh)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)
        assertTrue(outcome is WebSourceItemGate.Outcome.Fetched)
        assertEquals("Fetched", (outcome as WebSourceItemGate.Outcome.Fetched).item.title)
        coVerify(exactly = 1) { upserter.upsert(sourceId, fresh) }
        assertEquals(1, catalog.getItemCalls)
    }

    @Test fun `expired TTL fetches, upserts, and stamps`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)
        freshness.stamp(sourceId, itemId)

        clock.advance(WebSourceItemGate.TTL_MS + 1L) // now expired

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val fresh = sampleItem(title = "Refreshed")
        val catalog = RecordingCatalog(item = fresh)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)

        assertTrue(outcome is WebSourceItemGate.Outcome.Fetched)
        coVerify(exactly = 1) { upserter.upsert(sourceId, fresh) }
        assertEquals(1, catalog.getItemCalls)
        // Freshness was re-stamped to now.
        assertEquals(clock.nowMs(), dao.lastFetchedAt(sourceId, itemId))
    }

    @Test fun `expired TTL, fetch fails, existing row returns Stale without stamping`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)
        val stampedAt = clock.nowMs()
        freshness.stamp(sourceId, itemId)

        clock.advance(WebSourceItemGate.TTL_MS + 1L)

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val catalog = RecordingCatalog(throwOnGetItem = IOException("offline"))
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)
        assertEquals(WebSourceItemGate.Outcome.Stale, outcome)
        assertEquals(1, catalog.getItemCalls)
        assertEquals(stampedAt, dao.lastFetchedAt(sourceId, itemId))
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test fun `expired TTL, fetch fails, no row upserts the listing item and returns Failed`() = runTest {
        // Contract: Failed still leaves the caller with a Room row to navigate to. The gate
        // upserts the listing CatalogItem (whatever fields the browse HTML exposed) so tap →
        // detail doesn't dead-end when the detail fetch is offline or 429'd on first open.
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)

        val observer = FakeLibraryObserver(items = mutableMapOf()) // no row
        val catalog = RecordingCatalog(throwOnGetItem = IOException("offline"))
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val listing = sampleItem()
        val outcome = gate.openItem(sourceId, listing, catalog)
        assertTrue(outcome is WebSourceItemGate.Outcome.Failed)
        assertTrue((outcome as WebSourceItemGate.Outcome.Failed).cause is IOException)
        coVerify(exactly = 1) { upserter.upsert(sourceId, listing) }
        // A fallback upsert must NOT stamp — the next open has to retry the real fetch.
        assertEquals(null, dao.lastFetchedAt(sourceId, itemId))
    }

    @Test fun `forceRefresh bypasses freshness cache and refetches`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)
        freshness.stamp(sourceId, itemId)

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val forced = sampleItem(title = "Forced")
        val catalog = RecordingCatalog(item = forced)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog, forceRefresh = true)
        assertTrue(outcome is WebSourceItemGate.Outcome.Fetched)
        coVerify(exactly = 1) { upserter.upsert(sourceId, forced) }
        assertEquals(1, catalog.getItemCalls)
    }

    @Test fun `fetched item without cover url is upserted but not freshness-stamped`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)

        val observer = FakeLibraryObserver(items = mutableMapOf())
        val noCover = sampleItem().copy(coverUrl = null)
        val catalog = RecordingCatalog(item = noCover)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)

        assertTrue(outcome is WebSourceItemGate.Outcome.Fetched)
        coVerify(exactly = 1) { upserter.upsert(sourceId, noCover) }
        // Missing cover = incomplete fetch → no stamp → next open retries.
        assertEquals(null, dao.lastFetchedAt(sourceId, itemId))
    }

    @Test fun `fetched item with blank cover url is treated the same as null`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)

        val observer = FakeLibraryObserver(items = mutableMapOf())
        val blankCover = sampleItem().copy(coverUrl = "   ")
        val catalog = RecordingCatalog(item = blankCover)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)
        assertTrue(outcome is WebSourceItemGate.Outcome.Fetched)
        assertEquals(null, dao.lastFetchedAt(sourceId, itemId))
    }

    @Test fun `no-cover fetch causes next open to retry instead of serving Fresh`() = runTest {
        val clock = TestClock(1_000L)
        val dao = InMemoryFreshnessDao()
        val freshness = RemoteItemFreshness(dao, clock)

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val catalog = RecordingCatalog(item = sampleItem().copy(coverUrl = null))
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        // First open: no cover, no stamp.
        gate.openItem(sourceId, sampleItem(), catalog)
        assertEquals(1, catalog.getItemCalls)

        // Second open a moment later: TTL check must fail (no stamp) and we refetch.
        clock.advance(60_000L)
        gate.openItem(sourceId, sampleItem(), catalog)
        assertEquals("Second open should refetch, not serve Fresh from the cover-less row", 2, catalog.getItemCalls)
    }

    @Test fun `catalog returns null with existing row returns Stale`() = runTest {
        val clock = TestClock(1_000L)
        val freshness = RemoteItemFreshness(InMemoryFreshnessDao(), clock)

        val observer = FakeLibraryObserver(items = mutableMapOf(sourceId to (itemId to sampleLibraryItem())))
        val catalog = RecordingCatalog(item = null)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val outcome = gate.openItem(sourceId, sampleItem(), catalog)
        assertEquals(WebSourceItemGate.Outcome.Stale, outcome)
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test fun `catalog returns null with no row upserts the listing and returns Failed`() = runTest {
        // Mirror of the IOException Failed path — catalog reporting the item as missing (rare;
        // usually means the detail page 404'd) must still leave the caller with a row to open.
        val clock = TestClock(1_000L)
        val freshness = RemoteItemFreshness(InMemoryFreshnessDao(), clock)

        val observer = FakeLibraryObserver(items = mutableMapOf())
        val catalog = RecordingCatalog(item = null)
        val upserter = relaxedUpserter()
        val gate = WebSourceItemGate(observer, freshness, upserter, RecordingLogger())

        val listing = sampleItem()
        val outcome = gate.openItem(sourceId, listing, catalog)
        assertTrue(outcome is WebSourceItemGate.Outcome.Failed)
        assertTrue((outcome as WebSourceItemGate.Outcome.Failed).cause is NoSuchElementException)
        coVerify(exactly = 1) { upserter.upsert(sourceId, listing) }
    }
}

private class InMemoryFreshnessDao : RemoteItemFreshnessDao {
    private val rows = mutableMapOf<Pair<String, String>, Long>()
    override suspend fun upsert(entity: RemoteItemFreshnessEntity) {
        rows[entity.sourceId to entity.sourceItemId] = entity.lastFetchedAt
    }
    override suspend fun lastFetchedAt(sourceId: String, sourceItemId: String): Long? =
        rows[sourceId to sourceItemId]
    override suspend fun clear(sourceId: String, sourceItemId: String) {
        rows.remove(sourceId to sourceItemId)
    }
}

private class FakeLibraryObserver(
    val items: MutableMap<String, Pair<String, LibraryItem>>,
) : LibraryObserver {
    override fun observeLibraries(): Flow<List<Library>> = emptyFlow()
    override fun observeLibraries(sourceId: String): Flow<List<Library>> = emptyFlow()
    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeSeries(libraryId: String): Flow<List<Series>> = emptyFlow()
    override fun observeCollections(libraryId: String): Flow<List<Collection>> = emptyFlow()
    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = emptyFlow()
    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = emptyFlow()
    override suspend fun getItem(itemId: String): LibraryItem? =
        items.values.firstOrNull { it.first == itemId }?.second
    override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(getItemBlocking(itemId))
    private fun getItemBlocking(itemId: String): LibraryItem? =
        items.values.firstOrNull { it.first == itemId }?.second
    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? =
        items[sourceId]?.takeIf { it.first == itemId }?.second
    override suspend fun getLibrary(libraryId: String): Library? = null
    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
}

private class RecordingCatalog(
    private val item: CatalogItem? = null,
    private val throwOnGetItem: Throwable? = null,
) : Catalog {
    var getItemCalls: Int = 0
        private set

    override val sourceType: SourceType = SourceType.CHITANKA
    override suspend fun listRoots(): List<CatalogRoot> = emptyList()
    override suspend fun listFacets(rootId: String): List<CatalogFacet> = emptyList()
    override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
    override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
    override suspend fun getItem(itemId: String): CatalogItem? {
        getItemCalls++
        throwOnGetItem?.let { throw it }
        return item
    }
    override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
        throw UnsupportedOperationException()
    override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream =
        throw UnsupportedOperationException()
    override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
}
