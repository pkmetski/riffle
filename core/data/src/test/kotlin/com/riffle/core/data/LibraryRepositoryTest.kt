package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkSeriesItem
import com.riffle.core.network.NetworkUserMediaProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LibraryRepositoryTest {

    private val fakeServerRepository = object : SourceRepository {
        private val backing = MutableStateFlow<List<Source>>(emptyList())
        var activeServer: Source?
            get() = backing.value.firstOrNull { it.isActive }
            set(value) { backing.value = listOfNotNull(value) }
        fun setServers(servers: List<Source>) { backing.value = servers }
        override fun observeAll() = backing
        override suspend fun getActive() = backing.value.firstOrNull { it.isActive }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(IOException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val fakeTokenStorage = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(sourceId: String, token: String) { tokens[sourceId] = token }
        override suspend fun getToken(sourceId: String) = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) { tokens.remove(sourceId) }
    }

    private class FakeLibraryDao : LibraryDao {
        val upserted = mutableListOf<LibraryEntity>()
        private val roomData = mutableMapOf<String, MutableStateFlow<List<LibraryEntity>>>()

        fun seedData(sourceId: String, entities: List<LibraryEntity>) {
            roomData.getOrPut(sourceId) { MutableStateFlow(emptyList()) }.value = entities
        }

        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
            roomData.getOrPut(sourceId) { MutableStateFlow(emptyList()) }

        override suspend fun libraryIdsForSource(sourceId: String): List<String> =
            roomData[sourceId]?.value.orEmpty().map { it.id }

        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
            roomData[sourceId]?.value.orEmpty().firstOrNull { it.id == libraryId }

        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            upserted.addAll(libraries)
            libraries.groupBy { it.sourceId }.forEach { (sourceId, items) ->
                roomData.getOrPut(sourceId) { MutableStateFlow(emptyList()) }.value = items
            }
        }

        override suspend fun deleteBySourceId(sourceId: String) {
            roomData[sourceId]?.value = emptyList()
        }

        override suspend fun deleteById(sourceId: String, libraryId: String) {
            val flow = roomData[sourceId] ?: return
            flow.value = flow.value.filterNot { it.id == libraryId }
        }

        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            val flow = roomData[sourceId] ?: return
            flow.value = flow.value.map { if (it.id == libraryId) it.copy(isUnsupported = isUnsupported) else it }
        }
    }

    private class FakeSeriesDao : SeriesDao {
        val upsertedSeries = mutableListOf<SeriesEntity>()
        val upsertedItems = mutableListOf<SeriesItemEntity>()
        private val seriesData = mutableMapOf<String, MutableStateFlow<List<SeriesEntity>>>()
        private val itemData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

        override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> =
            seriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

        override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> =
            itemData.getOrPut(seriesId) { MutableStateFlow(emptyList()) }

        override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? = null

        private val continueSeriesData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

        override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
            continueSeriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

        fun seedContinueSeriesItems(libraryId: String, items: List<LibraryItemEntity>) {
            continueSeriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = items
        }

        override suspend fun upsertAll(series: List<SeriesEntity>) {
            upsertedSeries.addAll(series)
            series.groupBy { it.libraryId }.forEach { (libraryId, list) ->
                seriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = list
            }
        }

        override suspend fun upsertAllItems(items: List<SeriesItemEntity>) {
            upsertedItems.addAll(items)
        }

        override suspend fun deleteByLibraryId(libraryId: String) {
            seriesData[libraryId]?.value = emptyList()
        }

        override suspend fun deleteItemsByLibraryId(libraryId: String) {}

        fun seedItems(seriesId: String, items: List<LibraryItemEntity>) {
            itemData.getOrPut(seriesId) { MutableStateFlow(emptyList()) }.value = items
        }
    }

    private class FakeCollectionDao : CollectionDao {
        val upsertedCollections = mutableListOf<CollectionEntity>()
        val upsertedItems = mutableListOf<CollectionItemEntity>()
        private val collectionData = mutableMapOf<String, MutableStateFlow<List<CollectionEntity>>>()
        private val itemData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

        override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> =
            collectionData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

        override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> =
            itemData.getOrPut(collectionId) { MutableStateFlow(emptyList()) }

        override suspend fun upsertAll(collections: List<CollectionEntity>) {
            upsertedCollections.addAll(collections)
            collections.groupBy { it.libraryId }.forEach { (libraryId, list) ->
                collectionData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = list
            }
        }

        override suspend fun upsertAllItems(items: List<CollectionItemEntity>) {
            upsertedItems.addAll(items)
        }

        override suspend fun deleteByLibraryId(libraryId: String) {
            collectionData[libraryId]?.value = emptyList()
        }

        override suspend fun deleteItemsByLibraryId(libraryId: String) {}

        fun seedItems(collectionId: String, items: List<LibraryItemEntity>) {
            itemData.getOrPut(collectionId) { MutableStateFlow(emptyList()) }.value = items
        }
    }

    private fun makeRepo(
        libraryDao: FakeLibraryDao = FakeLibraryDao(),
        libraryItemDao: FakeLibraryItemDao = FakeLibraryItemDao(),
        seriesDao: FakeSeriesDao = FakeSeriesDao(),
        collectionDao: FakeCollectionDao = FakeCollectionDao(),
        api: AbsLibraryApi = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkCollection>> =
                NetworkResult.Success(emptyList())
        },
    ) = LibraryRepositoryImpl(
        InlineCatalogRegistry(testAbsCatalog(
            libraryApi = api,
            baseUrl = fakeServerRepository.activeServer?.url?.value ?: "http://abs",
        )),
        libraryDao, libraryItemDao, seriesDao, collectionDao,
        fakeServerRepository, com.riffle.core.domain.TestClock(),
    )

    private fun activeServer(id: String = "s1") = Source(
        id = id,
        url = SourceUrl.parse("https://abs.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    // ── refreshLibraries ─────────────────────────────────────────────────────

    @Test
    fun `refreshLibraries filters non-book libraries`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(listOf(
                    NetworkLibrary("lib-1", "Books", "book", audiobooksOnly = false),
                    NetworkLibrary("lib-2", "Audiobooks", "book", audiobooksOnly = true),
                    NetworkLibrary("lib-3", "Podcasts", "podcast", audiobooksOnly = false),
                ))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals(2, dao.upserted.size)
        assertTrue(dao.upserted.all { it.mediaType == "book" })
    }

    @Test
    fun `refreshLibraries caches to Room with correct sourceId`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(listOf(NetworkLibrary("lib-1", "Books", "book", audiobooksOnly = false)))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals("s1", dao.upserted[0].sourceId)
    }

    @Test
    fun `refreshLibraries returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Offline(IOException("timeout"))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    @Test
    fun `refreshLibraries returns NoActiveServer when no source configured`() = runTest {
        fakeServerRepository.activeServer = null
        val result = makeRepo().refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NoActiveServer)
    }

    // ── observeLibraries ─────────────────────────────────────────────────────

    @Test
    fun `observeLibraries emits from Room for active source`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryDao()
        dao.seedData("s1", listOf(LibraryEntity("lib-1", "Books", "book", "s1")))
        val result = makeRepo(libraryDao = dao).observeLibraries().first()
        assertEquals(1, result.size)
        assertEquals("lib-1", result[0].id)
    }

    @Test
    fun `observeLibraries emits empty list when no active source`() = runTest {
        fakeServerRepository.activeServer = null
        val result = makeRepo().observeLibraries().first()
        assertTrue(result.isEmpty())
    }

    // ── refreshLibraryItems ───────────────────────────────────────────────────

    @Test
    fun `refreshLibraryItems caches items to Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.42f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        val items = dao.itemsFor("lib-1")
        assertEquals(1, items.size)
        assertEquals("item-1", items[0].id)
        assertEquals(0.42f, items[0].readingProgress, 0.001f)
    }

    @Test
    fun `refreshLibraryItems deduplicates items with same title`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.5f, ebookFormat = EbookFormat.Epub),
                    NetworkLibraryItem("item-2", "lib-1", "my book", "Author A", 0.5f, ebookFormat = EbookFormat.Epub),
                    NetworkLibraryItem("item-3", "lib-1", "Other Book", "Author B", 0f, ebookFormat = EbookFormat.Epub),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(2, dao.itemsFor("lib-1").size)
    }

    @Test
    fun `refreshLibraryItems lifts lastOpenedAt from source mediaProgress when newer than local`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 1_000L, addedAt = 0L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
                com.riffle.core.network.NetworkResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.4f, lastUpdate = 5_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(5_000L, dao.itemsFor("lib-1").first { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `refreshLibraryItems keeps local lastOpenedAt when newer than source mediaProgress`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 9_000L, addedAt = 0L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
                com.riffle.core.network.NetworkResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.4f, lastUpdate = 1_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(9_000L, dao.itemsFor("lib-1").first { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `refreshLibraryItems keeps local readingProgress over stale source value`() = runTest {
        // Reproduces the offline-read regression: source has old 0.42, local has 0.75 from an
        // offline session. Refresh must not revert the library card back to the source value.
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.75f, addedAt = 0L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
                com.riffle.core.network.NetworkResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.42f, lastUpdate = 1_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.42f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(0.75f, dao.itemsFor("lib-1").first { it.id == "item-1" }.readingProgress, 0.001f)
    }

    @Test
    fun `refreshLibraryItems uses source readingProgress when no local record exists`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao() // no pre-existing items
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
                com.riffle.core.network.NetworkResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.80f, lastUpdate = 1_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(0.80f, dao.itemsFor("lib-1").first { it.id == "item-1" }.readingProgress, 0.001f)
    }

    // markItemOpened's session-push now lives in RecordItemOpened (see RecordItemOpenedTest).

    @Test
    fun `refreshLibraryItems preserves lastOpenedAt across refresh`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        // Seed an existing item with a known lastOpenedAt
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 99_000L, addedAt = 0L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(99_000L, dao.itemsFor("lib-1").first { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `refreshLibraryItems persists finishedAt from source progress`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", 1f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
                com.riffle.core.network.NetworkResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 1f, lastUpdate = 1_000L, finishedAt = 1_700_000_000_000L))
                )
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(1_700_000_000_000L, dao.itemsFor("lib-1").first().finishedAt)
    }

    @Test
    fun `refreshLibraryItems persists addedAt from network`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", null, ebookFormat = EbookFormat.Epub, addedAt = 1_708_369_906_982L)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(1_708_369_906_982L, dao.itemsFor("lib-1").first().addedAt)
    }

    @Test
    fun `refreshLibraryItems appends updatedAt as cache-buster to cover URL`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", null, ebookFormat = EbookFormat.Epub, updatedAt = 1_762_902_014_957L)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(
            "https://abs.example.com/api/items/item-1/cover?t=1762902014957",
            dao.itemsFor("lib-1").first().coverUrl,
        )
    }

    @Test
    fun `refreshLibraryItems uses plain cover URL when updatedAt is absent`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", null, ebookFormat = EbookFormat.Epub, updatedAt = null)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(
            "https://abs.example.com/api/items/item-1/cover",
            dao.itemsFor("lib-1").first().coverUrl,
        )
    }

    @Test
    fun `observeRecentlyAddedItems emits items from DAO ordered by addedAt`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "Older Book", "Author", null, 0f, addedAt = 1_000L),
            LibraryItemEntity("s1", "item-2", "lib-1", "Newer Book", "Author", null, 0f, addedAt = 2_000L),
        ))
        val result = makeRepo(libraryItemDao = dao).observeRecentlyAddedItems("lib-1").first()
        assertEquals(2, result.size)
        assertEquals(2_000L, result[0].addedAt)
        assertEquals(1_000L, result[1].addedAt)
    }

    @Test
    fun `refreshLibraryItems returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Offline(IOException("timeout"))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshLibraryItems("lib-1")
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    @Test
    fun `refreshLibraryItems persists hasAudio from network`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Foundation's Edge", "Asimov", null, ebookFormat = EbookFormat.Unsupported, hasAudio = true)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertTrue(dao.upserted[0].hasAudio)
    }

    @Test
    fun `hasAudio maps from entity to domain`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Audiobook", "Author", null, 0f, hasAudio = true, addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertTrue(item.hasAudio)
    }

    // ── observeLibraryItems ───────────────────────────────────────────────────

    @Test
    fun `observeLibraryItems emits from Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.5f, addedAt = 0L)))
        val result = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()
        assertEquals(1, result.size)
        assertEquals("item-1", result[0].id)
        assertFalse(result[0].isCached)
    }

    // ── issue #113: two Servers, colliding item ids — shelves follow the active Source ─────────

    @Test
    fun `marking read on the active source clears In Progress despite a stale duplicate on another source`() = runTest {
        // Two ABS Servers point at the same instance, so item ids and libraryId collide (issue
        // #113). markAsRead set the active Source's (s1) row to finished (1.0); the inactive
        // duplicate (s2) still holds the old fraction. The In Progress shelf must follow the
        // active Source, not surface the stale duplicate — otherwise the finished book stays
        // pinned to In Progress while book detail (which reads the active Source's row) shows 100%.
        fakeServerRepository.activeServer = activeServer(id = "s1")
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 1.0f, addedAt = 0L),
            LibraryItemEntity("s2", "item-1", "lib-1", "My Book", "Author A", null, 0.45f, addedAt = 0L),
        ))
        val repo = makeRepo(libraryItemDao = dao)

        val inProgress = repo.observeInProgressItems("lib-1").first()
        val finished = repo.observeFinishedItems("lib-1").first()

        assertTrue("a finished book must not remain in In Progress", inProgress.isEmpty())
        assertEquals(listOf("item-1"), finished.map { it.id })
    }

    @Test
    fun `library shelves re-resolve when active source changes`() = runTest {
        // ADR 0025: library-item flows resolve activeServerId via flatMapLatest, so when the user
        // switches Servers, subsequent emissions come only from the new Source's DAO scope —
        // never a stale snapshot of the previous Source's rows.
        fakeServerRepository.activeServer = activeServer(id = "s1")
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-shared", "S1 Book", "Author", null, 0.5f, addedAt = 0L),
            LibraryItemEntity("s2", "item-1", "lib-shared", "S2 Book", "Author", null, 0.2f, addedAt = 0L),
            LibraryItemEntity("s2", "item-99", "lib-shared", "S2 Other", "Author", null, 0.1f, addedAt = 0L),
        ))
        val repo = makeRepo(libraryItemDao = dao)

        val initial = repo.observeLibraryItems("lib-shared").first()
        assertEquals(listOf("item-1"), initial.map { it.id })
        assertEquals(0.5f, initial[0].readingProgress, 0.001f)

        fakeServerRepository.activeServer = activeServer(id = "s2")
        val afterSwitch = repo.observeLibraryItems("lib-shared").first()
        assertEquals(setOf("item-1", "item-99"), afterSwitch.map { it.id }.toSet())
        assertEquals(0.2f, afterSwitch.first { it.id == "item-1" }.readingProgress, 0.001f)
    }

    @Test
    fun `library shelves exclude rows owned by inactive duplicate servers`() = runTest {
        // The active Source (s1) has the in-progress copy; an inactive duplicate (s2) carries a
        // different fraction for the same id. Every shelf must show only the active Source's row.
        fakeServerRepository.activeServer = activeServer(id = "s1")
        val dao = FakeLibraryItemDao()
        // Seed the inactive duplicate first so a naive distinctBy{id} would keep the wrong row.
        dao.upsertAll(listOf(
            LibraryItemEntity("s2", "item-1", "lib-1", "My Book", "Author A", null, 0.9f, addedAt = 0L),
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.5f, addedAt = 0L),
        ))
        val repo = makeRepo(libraryItemDao = dao)

        val all = repo.observeLibraryItems("lib-1").first()
        val inProgress = repo.observeInProgressItems("lib-1").first()

        assertEquals(1, all.size)
        assertEquals(0.5f, all[0].readingProgress, 0.001f)
        assertEquals(listOf(0.5f), inProgress.map { it.readingProgress })
    }

    // ── toDomain: ebookFormat → isReadable (regression) ─────────────────────

    @Test
    fun `epub ebookFormat maps to isReadable true`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "epub", addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Epub, item.ebookFormat)
        assertTrue(item.isReadable)
    }

    @Test
    fun `pdf ebookFormat maps to isReadable true`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "pdf", addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Pdf, item.ebookFormat)
        assertTrue(item.isReadable)
    }

    @Test
    fun `unsupported ebookFormat maps to isReadable false`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "unsupported", addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isReadable)
    }

    @Test
    fun `unknown ebookFormat string maps to isReadable false`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        // "azw3" is not in the supported set — must fall back to Unsupported. (CBZ was previously
        // used as the "unknown" sentinel; it's now a supported format — ADR 0042.)
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Book", "Author", null, 0f, ebookFormat = "azw3", addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isReadable)
    }

    @Test
    fun `cbz ebookFormat string maps to Cbz and isReadable true`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Comic", "Author", null, 0f, ebookFormat = "cbz", addedAt = 0L)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Cbz, item.ebookFormat)
        assertTrue(item.isReadable)
    }

    // ── refreshLibraryItems: format round-trip ────────────────────────────────

    @Test
    fun `refreshLibraryItems stores epub format and item is observed as supported`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", 0f, EbookFormat.Epub),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        val repo = makeRepo(libraryItemDao = dao, api = api)
        repo.refreshLibraryItems("lib-1")
        val item = repo.observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Epub, item.ebookFormat)
        assertTrue(item.isReadable)
    }

    @Test
    fun `refreshLibraryItems stores null ebookFormat as unsupported`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Audiobook", "Author", 0f, EbookFormat.Unsupported),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        val repo = makeRepo(libraryItemDao = dao, api = api)
        repo.refreshLibraryItems("lib-1")
        val item = repo.observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isReadable)
    }

    // ── refreshSeries ─────────────────────────────────────────────────────────

    @Test
    fun `refreshSeries fetches from API and caches series to Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeSeriesDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(listOf(
                    NetworkSeries("ser-1", "lib-1", "Stormlight", listOf(
                        NetworkSeriesItem("item-1", "lib-1", "WoK", "Sanderson", "1", 0.5f, EbookFormat.Epub),
                        NetworkSeriesItem("item-2", "lib-1", "WoR", "Sanderson", "2", 0f, EbookFormat.Epub),
                    )),
                ))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(seriesDao = dao, api = api).refreshSeries("lib-1")
        assertEquals(1, dao.upsertedSeries.size)
        assertEquals("ser-1", dao.upsertedSeries[0].id)
        assertEquals(2, dao.upsertedSeries[0].bookCount)
        assertEquals(2, dao.upsertedItems.size)
        assertEquals(1f, dao.upsertedItems[0].sequenceOrder, 0.001f)
        assertEquals(2f, dao.upsertedItems[1].sequenceOrder, 0.001f)
    }

    @Test
    fun `refreshSeries returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Offline(IOException("timeout"))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshSeries("lib-1")
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    // Regression: LocalFilesCatalog delivers items in SeriesEntryOrdering order but only some
    // carry a numeric sequence. If refreshSeries fell back to (index+1) for null sequences, a
    // book with real sequence "10" would land at sequenceOrder=10.0 while an unnumbered book
    // at list index 2 would land at 3.0 — reordering "10" AFTER the unnumbered one when the
    // DAO sorts by sequenceOrder ASC. The fix shifts null-sequence rows past the max numeric.
    @Test
    fun `refreshSeries places null-sequence entries after every numeric entry in the same series`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeSeriesDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(listOf(
                    NetworkSeries("ser-1", "lib-1", "Cycle", listOf(
                        NetworkSeriesItem("num-1", "lib-1", "One", "A", "1", 0f, EbookFormat.Epub),
                        NetworkSeriesItem("num-10", "lib-1", "Ten", "A", "10", 0f, EbookFormat.Epub),
                        NetworkSeriesItem("no-seq", "lib-1", "Extra", "A", null, 0f, EbookFormat.Epub),
                    )),
                ))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(seriesDao = dao, api = api).refreshSeries("lib-1")
        val ordered = dao.upsertedItems.sortedBy { it.sequenceOrder }
        assertEquals(listOf("num-1", "num-10", "no-seq"), ordered.map { it.itemId })
        // Extra's sequenceOrder must exceed 10 so it doesn't slot between "1" and "10" — the
        // whole point of the fix. (Old (index+1) fallback would have given it 3.0.)
        val extra = dao.upsertedItems.first { it.itemId == "no-seq" }
        assertTrue(
            "null-sequence sequenceOrder (${extra.sequenceOrder}) must exceed the max numeric (10)",
            extra.sequenceOrder > 10f,
        )
    }

    @Test
    fun `refreshSeries uses first book updatedAt as cache-buster in cover URL`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeSeriesDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(listOf(
                    NetworkSeries("ser-1", "lib-1", "Stormlight", listOf(
                        NetworkSeriesItem("item-1", "lib-1", "WoK", "Sanderson", "1", 0f, EbookFormat.Epub, updatedAt = 1_762_902_014_957L),
                        NetworkSeriesItem("item-2", "lib-1", "WoR", "Sanderson", "2", 0f, EbookFormat.Epub, updatedAt = 1_762_000_000_000L),
                    )),
                ))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(seriesDao = dao, api = api).refreshSeries("lib-1")
        assertEquals(
            "https://abs.example.com/api/items/item-1/cover?t=1762902014957",
            dao.upsertedSeries.first().coverUrl,
        )
    }

    @Test
    fun `refreshSeries uses plain cover URL when first book updatedAt is absent`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeSeriesDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(listOf(
                    NetworkSeries("ser-1", "lib-1", "Stormlight", listOf(
                        NetworkSeriesItem("item-1", "lib-1", "WoK", "Sanderson", "1", 0f, EbookFormat.Epub, updatedAt = null),
                    )),
                ))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(emptyList())
        }
        makeRepo(seriesDao = dao, api = api).refreshSeries("lib-1")
        assertEquals(
            "https://abs.example.com/api/items/item-1/cover",
            dao.upsertedSeries.first().coverUrl,
        )
    }

    // ── observeSeries ─────────────────────────────────────────────────────────

    @Test
    fun `observeSeries emits from Room scoped to libraryId`() = runTest {
        val dao = FakeSeriesDao()
        dao.upsertAll(listOf(SeriesEntity("ser-1", "lib-1", "Stormlight", null, 2)))
        val result = makeRepo(seriesDao = dao).observeSeries("lib-1").first()
        assertEquals(1, result.size)
        assertEquals("ser-1", result[0].id)
        assertEquals("Stormlight", result[0].name)
    }

    // ── refreshCollections ────────────────────────────────────────────────────

    @Test
    fun `refreshCollections fetches from API and caches collections to Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeCollectionDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Success(listOf(
                    NetworkCollection("col-1", "lib-1", "Favorites", listOf(
                        NetworkLibraryItem("item-1", "lib-1", "Book A", "Author", 0.3f, EbookFormat.Epub),
                        NetworkLibraryItem("item-2", "lib-1", "Book B", "Author", 0f, EbookFormat.Epub),
                    )),
                ))
        }
        makeRepo(collectionDao = dao, api = api).refreshCollections("lib-1")
        assertEquals(1, dao.upsertedCollections.size)
        assertEquals("col-1", dao.upsertedCollections[0].id)
        assertEquals(2, dao.upsertedCollections[0].bookCount)
        assertEquals(2, dao.upsertedItems.size)
    }

    @Test
    fun `refreshCollections returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
                NetworkResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
                NetworkResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
                NetworkResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
                NetworkResult.Offline(IOException("timeout"))
        }
        val result = makeRepo(api = api).refreshCollections("lib-1")
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    // ── observeCollections ────────────────────────────────────────────────────

    @Test
    fun `observeCollections emits from Room scoped to libraryId`() = runTest {
        val dao = FakeCollectionDao()
        dao.upsertAll(listOf(CollectionEntity("col-1", "lib-1", "Favorites", 3)))
        val result = makeRepo(collectionDao = dao).observeCollections("lib-1").first()
        assertEquals(1, result.size)
        assertEquals("col-1", result[0].id)
        assertEquals("Favorites", result[0].name)
    }

    // ── observeSeriesItems ────────────────────────────────────────────────────

    @Test
    fun `observeSeriesItems emits items from Room in series order`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeSeriesDao()
        val item1 = LibraryItemEntity("s1", "item-1", "lib-1", "WoK", "Sanderson", null, 0.5f, addedAt = 0L)
        val item2 = LibraryItemEntity("s1", "item-2", "lib-1", "WoR", "Sanderson", null, 0f, addedAt = 0L)
        dao.seedItems("ser-1", listOf(item1, item2))
        val result = makeRepo(seriesDao = dao).observeSeriesItems("ser-1").first()
        assertEquals(2, result.size)
        assertEquals("item-1", result[0].id)
        assertEquals("item-2", result[1].id)
    }

    // ── observeContinueSeriesItems ────────────────────────────────────────────

    @Test
    fun `observeContinueSeriesItems maps entities from DAO`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeSeriesDao()
        val repo = makeRepo(seriesDao = dao)
        dao.seedContinueSeriesItems("lib-1", listOf(
            LibraryItemEntity(
                sourceId = "s1", id = "item-42", libraryId = "lib-1",
                title = "Abaddon's Gate", author = "James S. A. Corey",
                coverUrl = null, readingProgress = 0f, addedAt = 0L,
            ),
            LibraryItemEntity(
                sourceId = "s1", id = "item-43", libraryId = "lib-1",
                title = "Cibola Burn", author = "James S. A. Corey",
                coverUrl = null, readingProgress = 0f, addedAt = 0L,
            ),
        ))

        val result = repo.observeContinueSeriesItems("lib-1").first()

        assertEquals(2, result.size)
        assertEquals("item-42", result[0].id)
        assertEquals("Abaddon's Gate", result[0].title)
        assertEquals("James S. A. Corey", result[0].author)
        assertEquals("item-43", result[1].id)
    }

    // ── observeCollectionItems ────────────────────────────────────────────────

    @Test
    fun `observeCollectionItems emits items from Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeCollectionDao()
        val item1 = LibraryItemEntity("s1", "item-1", "lib-1", "Book A", "Author", null, 0f, addedAt = 0L)
        dao.seedItems("col-1", listOf(item1))
        val result = makeRepo(collectionDao = dao).observeCollectionItems("col-1").first()
        assertEquals(1, result.size)
        assertEquals("item-1", result[0].id)
    }

    // ── Storyteller refresh ───────────────────────────────────────────────────

    private fun storytellerServer() = Source(
        id = "st-1",
        url = SourceUrl.parse("http://media-source:8001")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.domain.ServerType.STORYTELLER_SERVICE,
    )

    // Storyteller is never the active/browsable source (ADR 0026), so refreshLibraryItems/
    // refreshSeries/refreshCollections no longer have a Storyteller branch. The readaloud-matcher /
    // syncer dispatch lives in the RefreshLibraryItems use-case — see core/domain tests.
}
