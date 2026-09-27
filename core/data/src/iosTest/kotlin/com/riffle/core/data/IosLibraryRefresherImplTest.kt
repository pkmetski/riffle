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
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.KomgaLibraryInfo
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.sync.DirtyProgressLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [IosLibraryRefresherImpl.refreshItemProgress] — the path the detail screen takes on iOS to
 * pull a single item's current progress from the source and write it to the library_items table.
 *
 * The Android equivalent is [LibraryRepositoryTest.refreshItemProgress adopts server ebookProgress
 * into library_items on a clean row]. These tests together ensure both hosts call through to the
 * DAO with the fraction derived from the source response — not a stale cached value.
 */
class IosLibraryRefresherImplTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun absSource(id: String = "s1") = Source(
        id = id,
        url = SourceUrl.parse("http://abs.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
    )

    private class FixedSourceRepository(private val source: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOfNotNull(source))
        override suspend fun getActive(): Source? = source
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FixedTokenStorage(private val token: String?) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = token
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    /** [ProgressPeerCapability] that returns a canned [CatalogProgress] from [pullProgress]. */
    private class FakeProgressPeer(
        private val progress: CatalogProgress,
        override val sourceType: SourceType = SourceType.ABS,
    ) : Catalog, ProgressPeerCapability {
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

    private class RecordingLibraryItemDao : LibraryItemDao by ThrowingLibraryItemDao {
        val progressUpdates = mutableListOf<Triple<String, String, Float>>()
        val finishedAtUpdates = mutableListOf<Triple<String, String, Long?>>()
        private val rows = mutableMapOf<String, LibraryItemEntity>()

        fun seed(entity: LibraryItemEntity) { rows[entity.id] = entity }
        fun getById(itemId: String): LibraryItemEntity? = rows[itemId]

        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            progressUpdates += Triple(sourceId, itemId, progress)
            rows[itemId] = rows[itemId]?.copy(readingProgress = progress) ?: return
        }
        override suspend fun updateReadingProgressStamped(sourceId: String, itemId: String, progress: Float, updatedAt: Long) {
            progressUpdates += Triple(sourceId, itemId, progress)
            rows[itemId] = rows[itemId]?.copy(readingProgress = progress, progressServerUpdatedAt = updatedAt) ?: return
        }
        override suspend fun updateReadingProgressFromServer(sourceId: String, itemId: String, progress: Float, serverUpdatedAt: Long) {
            val existing = rows[itemId]
            if (existing != null && serverUpdatedAt < existing.progressServerUpdatedAt) return
            progressUpdates += Triple(sourceId, itemId, progress)
            if (existing != null) {
                rows[itemId] = existing.copy(readingProgress = progress, progressServerUpdatedAt = serverUpdatedAt)
            }
        }
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {
            finishedAtUpdates += Triple(sourceId, itemId, finishedAt)
        }
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> = emptyList()
        override suspend fun replaceAllForLibrary(sourceId: String, libraryId: String, items: List<LibraryItemEntity>) = Unit
    }

    private class EmptyDirtyLedger : DirtyProgressLedger {
        override suspend fun serversWithDirty(): List<String> = emptyList()
        override suspend fun dirtyEbookItems(sourceId: String): List<String> = emptyList()
        override suspend fun dirtyAudioItems(sourceId: String): List<String> = emptyList()
    }

    private object NoopLibraryDao : LibraryDao {
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> = flowOf(emptyList())
        override suspend fun libraryIdsForSource(sourceId: String): List<String> = emptyList()
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? = null
        override suspend fun upsertAll(libraries: List<LibraryEntity>) = Unit
        override suspend fun deleteBySourceId(sourceId: String) = Unit
        override suspend fun deleteById(sourceId: String, libraryId: String) = Unit
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) = Unit
    }

    private object NoopSeriesDao : SeriesDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> = flowOf(emptyList())
        override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeContinueSeriesAllSources(): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(series: List<SeriesEntity>) = Unit
        override suspend fun upsertAllItems(items: List<SeriesItemEntity>) = Unit
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
        override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private object NoopCollectionDao : CollectionDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = flowOf(emptyList())
        override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(collections: List<CollectionEntity>) = Unit
        override suspend fun upsertAllItems(items: List<CollectionItemEntity>) = Unit
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
    }

    private object NoopAbsLibraryApi : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
            throw UnsupportedOperationException()
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
            throw UnsupportedOperationException()
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
            throw UnsupportedOperationException()
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
            throw UnsupportedOperationException()
    }

    private object NoopKomgaLibraryApi : KomgaLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<KomgaLibraryInfo>> =
            throw UnsupportedOperationException()
        override suspend fun fetchCbzPageCount(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): Int =
            throw UnsupportedOperationException()
        override suspend fun fetchCbzPage(baseUrl: String, bookId: String, pageIndex: Int, maxWidth: Int?, token: String, insecureAllowed: Boolean): ByteArray =
            throw UnsupportedOperationException()
    }

    private fun refresher(
        source: Source? = absSource(),
        token: String? = "tok",
        catalog: Catalog,
        dao: RecordingLibraryItemDao = RecordingLibraryItemDao(),
        ledger: DirtyProgressLedger = EmptyDirtyLedger(),
    ) = IosLibraryRefresherImpl(
        sourceRepository = FixedSourceRepository(source),
        tokenStorage = FixedTokenStorage(token),
        absLibraryApi = NoopAbsLibraryApi,
        libraryDao = NoopLibraryDao,
        komgaLibraryApi = NoopKomgaLibraryApi,
        seriesDao = NoopSeriesDao,
        collectionDao = NoopCollectionDao,
        libraryItemDao = dao,
        catalogRegistry = FixedCatalogRegistry(catalog),
        dirtyProgressLedger = ledger,
    )

    // ── refreshItemProgress ───────────────────────────────────────────────────

    @Test
    fun refreshItemProgressWritesDerivedFractionToDao() = runTest {
        // Regression: iOS's refreshItemProgress was a no-op stub (returned Success without
        // calling pullProgress or touching the DAO). The detail screen relies on this path
        // (triggered on ON_RESUME) to surface progress advanced on another device.
        val dao = RecordingLibraryItemDao()
        val peer = FakeProgressPeer(
            CatalogProgress(
                itemId = "item-1",
                ebookProgress = 0.6f,
                isFinished = false,
                lastUpdate = 5_000L,
            )
        )
        refresher(catalog = peer, dao = dao).refreshItemProgress("s1", "item-1")
        assertEquals(1, dao.progressUpdates.size, "updateReadingProgress was never called")
        assertEquals(Triple("s1", "item-1", 0.6f), dao.progressUpdates.first())
    }

    @Test
    fun refreshItemProgressSkipsWhenPositionRowIsDirty() = runTest {
        // A dirty ebook row means a pending offline edit is in flight — the ADR-0036 sweep owns
        // this item. The detail screen pull must not clobber 0.75 with the stale server value.
        val dao = RecordingLibraryItemDao()
        dao.seed(LibraryItemEntity("s1", "item-1", "lib-1", "Book", "A", null, 0.75f, addedAt = 0L))
        val peer = FakeProgressPeer(
            CatalogProgress(itemId = "item-1", ebookProgress = 0.42f, isFinished = false, lastUpdate = 5_000L)
        )
        val dirtyLedger = object : DirtyProgressLedger {
            override suspend fun serversWithDirty(): List<String> = listOf("s1")
            override suspend fun dirtyEbookItems(sourceId: String): List<String> = listOf("item-1")
            override suspend fun dirtyAudioItems(sourceId: String): List<String> = emptyList()
        }
        refresher(catalog = peer, dao = dao, ledger = dirtyLedger).refreshItemProgress("s1", "item-1")
        assertEquals(0, dao.progressUpdates.size, "updateReadingProgress must not be called for dirty rows")
    }

    @Test
    fun refreshItemProgressDoesNothingWhenNoActiveSource() = runTest {
        val dao = RecordingLibraryItemDao()
        val peer = FakeProgressPeer(CatalogProgress(itemId = "item-1", ebookProgress = 0.5f, isFinished = false, lastUpdate = 0L))
        refresher(source = null, catalog = peer, dao = dao).refreshItemProgress("s1", "item-1")
        assertEquals(0, dao.progressUpdates.size)
    }
}
