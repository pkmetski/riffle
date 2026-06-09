package com.riffle.core.data

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
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkSeriesItem
import com.riffle.core.network.NetworkSeriesResult
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

    private val fakeServerRepository = object : ServerRepository {
        var activeServer: Server? = null
        override fun observeAll() = MutableStateFlow(listOfNotNull(activeServer))
        override suspend fun getActive() = activeServer
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
            AuthenticateResult.NetworkError(IOException())
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
            CommitServerResult.Failure(IOException())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private val fakeTokenStorage = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun getToken(serverId: String) = tokens[serverId]
        override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
    }

    private class FakeLibraryDao : LibraryDao {
        val upserted = mutableListOf<LibraryEntity>()
        private val roomData = mutableMapOf<String, MutableStateFlow<List<LibraryEntity>>>()

        fun seedData(serverId: String, entities: List<LibraryEntity>) {
            roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }.value = entities
        }

        override fun observeByServerId(serverId: String): Flow<List<LibraryEntity>> =
            roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }

        override suspend fun libraryIdsForServer(serverId: String): List<String> =
            roomData[serverId]?.value.orEmpty().map { it.id }

        override suspend fun getById(libraryId: String): LibraryEntity? =
            roomData.values.flatMap { it.value }.firstOrNull { it.id == libraryId }

        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            upserted.addAll(libraries)
            libraries.groupBy { it.serverId }.forEach { (serverId, items) ->
                roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }.value = items
            }
        }

        override suspend fun deleteByServerId(serverId: String) {
            roomData[serverId]?.value = emptyList()
        }

        override suspend fun setUnsupported(libraryId: String, isUnsupported: Boolean) {
            val current = roomData[libraryId]?.value ?: return
            roomData[libraryId]?.value = current.map { it.copy(isUnsupported = isUnsupported) }
        }
    }

    private class FakeSeriesDao : SeriesDao {
        val upsertedSeries = mutableListOf<SeriesEntity>()
        val upsertedItems = mutableListOf<SeriesItemEntity>()
        private val seriesData = mutableMapOf<String, MutableStateFlow<List<SeriesEntity>>>()
        private val itemData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

        override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> =
            seriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

        override fun observeItemsBySeriesId(seriesId: String): Flow<List<LibraryItemEntity>> =
            itemData.getOrPut(seriesId) { MutableStateFlow(emptyList()) }

        override suspend fun findSeriesIdForItem(serverId: String, itemId: String): String? = null

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

        override fun observeItemsByCollectionId(collectionId: String): Flow<List<LibraryItemEntity>> =
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        },
        readingSessionRepository: com.riffle.core.domain.ReadingSessionRepository = NoopReadingSessionRepository,
        readaloudMatchingService: ReadaloudMatchingService = noopMatchingService(libraryItemDao),
        storytellerReadaloudSyncer: StorytellerReadaloudSyncer = StorytellerReadaloudSyncer(
            fakeServerRepository, fakeTokenStorage, storytellerApiReturning(emptyList()), libraryItemDao, { 0L },
        ),
    ) = LibraryRepositoryImpl(
        api, libraryDao, libraryItemDao, seriesDao, collectionDao,
        fakeServerRepository, fakeTokenStorage, readingSessionRepository, readaloudMatchingService,
        storytellerReadaloudSyncer,
    )

    private fun noopMatchingService(itemDao: FakeLibraryItemDao): ReadaloudMatchingService =
        ReadaloudMatchingService(itemDao, NoopReadaloudLinkDao, NoopReadaloudCandidateDao, NoopReadaloudDismissalDao)

    private val storytellerApiNotCalled = object : com.riffle.core.network.StorytellerLibraryApi {
        override suspend fun validateToken(baseUrl: String, token: String, insecureAllowed: Boolean) =
            error("Storyteller library API should not be called from ABS tests")
        override suspend fun listReadalouds(baseUrl: String, token: String, insecureAllowed: Boolean) =
            error("Storyteller library API should not be called from ABS tests")
        override suspend fun getBook(baseUrl: String, bookId: Long, token: String, insecureAllowed: Boolean) =
            error("Storyteller library API should not be called from ABS tests")
        override fun coverUrl(baseUrl: String, bookId: Long) =
            error("Storyteller library API should not be called from ABS tests")
    }

    private object NoopReadingSessionRepository : com.riffle.core.domain.ReadingSessionRepository {
        override suspend fun syncProgress(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
            com.riffle.core.domain.SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
            com.riffle.core.domain.ProgressSyncCycleResult.InSync
        override suspend fun setProgress(itemId: String, progress: Float) = Unit
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
    }

    private class RecordingReadingSessionRepository : com.riffle.core.domain.ReadingSessionRepository {
        val touchedItemIds = mutableListOf<String>()
        override suspend fun syncProgress(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
            com.riffle.core.domain.SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: com.riffle.core.domain.SessionPayload) =
            com.riffle.core.domain.ProgressSyncCycleResult.InSync
        override suspend fun setProgress(itemId: String, progress: Float) = Unit
        override suspend fun touchOpenTimestamp(itemId: String) { touchedItemIds += itemId }
    }

    private fun activeServer(id: String = "s1") = Server(
        id = id,
        url = ServerUrl.parse("https://abs.example.com")!!,
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(listOf(
                    NetworkLibrary("lib-1", "Books", "book", audiobooksOnly = false),
                    NetworkLibrary("lib-2", "Audiobooks", "book", audiobooksOnly = true),
                    NetworkLibrary("lib-3", "Podcasts", "podcast", audiobooksOnly = false),
                ))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals(2, dao.upserted.size)
        assertTrue(dao.upserted.all { it.mediaType == "book" })
    }

    @Test
    fun `refreshLibraries caches to Room with correct serverId`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(listOf(NetworkLibrary("lib-1", "Books", "book", audiobooksOnly = false)))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals("s1", dao.upserted[0].serverId)
    }

    @Test
    fun `refreshLibraries returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.NetworkError(IOException("timeout"))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    @Test
    fun `refreshLibraries returns NoActiveServer when no server configured`() = runTest {
        fakeServerRepository.activeServer = null
        val result = makeRepo().refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NoActiveServer)
    }

    // ── observeLibraries ─────────────────────────────────────────────────────

    @Test
    fun `observeLibraries emits from Room for active server`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryDao()
        dao.seedData("s1", listOf(LibraryEntity("lib-1", "Books", "book", "s1")))
        val result = makeRepo(libraryDao = dao).observeLibraries().first()
        assertEquals(1, result.size)
        assertEquals("lib-1", result[0].id)
    }

    @Test
    fun `observeLibraries emits empty list when no active server`() = runTest {
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.42f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(1, dao.upserted.size)
        assertEquals("item-1", dao.upserted[0].id)
        assertEquals(0.42f, dao.upserted[0].readingProgress, 0.001f)
    }

    @Test
    fun `refreshLibraryItems deduplicates items with same title`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.5f, ebookFormat = EbookFormat.Epub),
                    NetworkLibraryItem("item-2", "lib-1", "my book", "Author A", 0.5f, ebookFormat = EbookFormat.Epub),
                    NetworkLibraryItem("item-3", "lib-1", "Other Book", "Author B", 0f, ebookFormat = EbookFormat.Epub),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(2, dao.upserted.size)
    }

    @Test
    fun `refreshLibraryItems lifts lastOpenedAt from server mediaProgress when newer than local`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 1_000L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean) =
                com.riffle.core.network.NetworkUserProgressResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.4f, lastUpdate = 5_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(5_000L, dao.upserted.last { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `refreshLibraryItems keeps local lastOpenedAt when newer than server mediaProgress`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 9_000L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean) =
                com.riffle.core.network.NetworkUserProgressResult.Success(
                    mapOf("item-1" to com.riffle.core.network.NetworkUserMediaProgress(ebookProgress = 0.4f, lastUpdate = 1_000L))
                )
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(9_000L, dao.upserted.last { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `markItemOpened pushes touchOpenTimestamp to the session repository`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val session = RecordingReadingSessionRepository()
        makeRepo(readingSessionRepository = session).markItemOpened("item-42")
        assertEquals(listOf("item-42"), session.touchedItemIds)
    }

    @Test
    fun `refreshLibraryItems preserves lastOpenedAt across refresh`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        // Seed an existing item with a known lastOpenedAt
        dao.upsertAll(listOf(
            LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.4f, lastOpenedAt = 99_000L),
        ))
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.4f, ebookFormat = EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(99_000L, dao.upserted.last { it.id == "item-1" }.lastOpenedAt)
    }

    @Test
    fun `refreshLibraryItems persists addedAt from network`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", null, ebookFormat = EbookFormat.Epub, addedAt = 1_708_369_906_982L)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(1_708_369_906_982L, dao.upserted[0].addedAt)
    }

    @Test
    fun `observeRecentlyAddedItems emits items from DAO ordered by addedAt`() = runTest {
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.NetworkError(IOException("timeout"))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Foundation's Edge", "Asimov", null, ebookFormat = EbookFormat.Unsupported, hasAudio = true)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertTrue(dao.upserted[0].hasAudio)
    }

    @Test
    fun `hasAudio maps from entity to domain`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Audiobook", "Author", null, 0f, hasAudio = true)))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertTrue(item.hasAudio)
    }

    // ── observeLibraryItems ───────────────────────────────────────────────────

    @Test
    fun `observeLibraryItems emits from Room`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "My Book", "Author A", null, 0.5f)))
        val result = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()
        assertEquals(1, result.size)
        assertEquals("item-1", result[0].id)
        assertFalse(result[0].isCached)
    }

    // ── toDomain: ebookFormat → isSupported (regression) ─────────────────────

    @Test
    fun `epub ebookFormat maps to isSupported true`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "epub")))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Epub, item.ebookFormat)
        assertTrue(item.isSupported)
    }

    @Test
    fun `pdf ebookFormat maps to isSupported true`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "pdf")))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Pdf, item.ebookFormat)
        assertTrue(item.isSupported)
    }

    @Test
    fun `unsupported ebookFormat maps to isSupported false`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Dune", "Herbert", null, 0f, ebookFormat = "unsupported")))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isSupported)
    }

    @Test
    fun `unknown ebookFormat string maps to isSupported false`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("s1", "item-1", "lib-1", "Comic", "Author", null, 0f, ebookFormat = "cbz")))
        val item = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isSupported)
    }

    // ── refreshLibraryItems: format round-trip ────────────────────────────────

    @Test
    fun `refreshLibraryItems stores epub format and item is observed as supported`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", 0f, EbookFormat.Epub),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        val repo = makeRepo(libraryItemDao = dao, api = api)
        repo.refreshLibraryItems("lib-1")
        val item = repo.observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Epub, item.ebookFormat)
        assertTrue(item.isSupported)
    }

    @Test
    fun `refreshLibraryItems stores null ebookFormat as unsupported`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Audiobook", "Author", 0f, EbookFormat.Unsupported),
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        val repo = makeRepo(libraryItemDao = dao, api = api)
        repo.refreshLibraryItems("lib-1")
        val item = repo.observeLibraryItems("lib-1").first()[0]
        assertEquals(EbookFormat.Unsupported, item.ebookFormat)
        assertFalse(item.isSupported)
    }

    // ── refreshSeries ─────────────────────────────────────────────────────────

    @Test
    fun `refreshSeries fetches from API and caches series to Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeSeriesDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(listOf(
                    NetworkSeries("ser-1", "lib-1", "Stormlight", listOf(
                        NetworkSeriesItem("item-1", "lib-1", "WoK", "Sanderson", "1", 0.5f, EbookFormat.Epub),
                        NetworkSeriesItem("item-2", "lib-1", "WoR", "Sanderson", "2", 0f, EbookFormat.Epub),
                    )),
                ))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.NetworkError(IOException("timeout"))
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshSeries("lib-1")
        assertTrue(result is LibraryRefreshResult.NetworkError)
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(listOf(
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
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.NetworkError(IOException("timeout"))
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
        val dao = FakeSeriesDao()
        val item1 = LibraryItemEntity("s1", "item-1", "lib-1", "WoK", "Sanderson", null, 0.5f)
        val item2 = LibraryItemEntity("s1", "item-2", "lib-1", "WoR", "Sanderson", null, 0f)
        dao.seedItems("ser-1", listOf(item1, item2))
        val result = makeRepo(seriesDao = dao).observeSeriesItems("ser-1").first()
        assertEquals(2, result.size)
        assertEquals("item-1", result[0].id)
        assertEquals("item-2", result[1].id)
    }

    // ── observeCollectionItems ────────────────────────────────────────────────

    @Test
    fun `observeCollectionItems emits items from Room`() = runTest {
        val dao = FakeCollectionDao()
        val item1 = LibraryItemEntity("s1", "item-1", "lib-1", "Book A", "Author", null, 0f)
        dao.seedItems("col-1", listOf(item1))
        val result = makeRepo(collectionDao = dao).observeCollectionItems("col-1").first()
        assertEquals(1, result.size)
        assertEquals("item-1", result[0].id)
    }

    // ── Storyteller refresh ───────────────────────────────────────────────────

    private fun storytellerServer() = Server(
        id = "st-1",
        url = ServerUrl.parse("http://media-server:8001")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.domain.ServerType.STORYTELLER,
    )

    private fun storytellerApiReturning(
        books: List<com.riffle.core.network.NetworkStorytellerBook>,
    ) = object : com.riffle.core.network.StorytellerLibraryApi {
        override suspend fun validateToken(baseUrl: String, token: String, insecureAllowed: Boolean) =
            com.riffle.core.network.NetworkStorytellerValidateResult.Valid
        override suspend fun listReadalouds(baseUrl: String, token: String, insecureAllowed: Boolean) =
            com.riffle.core.network.NetworkStorytellerBooksResult.Success(books)
        override suspend fun getBook(baseUrl: String, bookId: Long, token: String, insecureAllowed: Boolean) =
            com.riffle.core.network.NetworkStorytellerBookResult.NotFound(bookId)
        override fun coverUrl(baseUrl: String, bookId: Long) = "$baseUrl/api/books/$bookId/cover"
    }

    // Storyteller is never the active/browsable server (ADR 0026), so refreshLibraryItems/
    // refreshSeries/refreshCollections no longer have a Storyteller branch. Its readaloud rows are
    // synced as matcher input via the StorytellerReadaloudSyncer (covered below).

    // ── ABS refresh triggers storyteller syncer ───────────────────────────────

    @Test
    fun `ABS library refresh invokes storyteller syncer before reconcile`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val itemDao = FakeLibraryItemDao()
        var synced = false
        val spySyncer = object : StorytellerReadaloudSyncer(
            fakeServerRepository, fakeTokenStorage, storytellerApiReturning(emptyList()), itemDao, { 0L },
        ) {
            override suspend fun syncStale() { synced = true }
        }
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "Dune", "Herbert", 0f, ebookFormat = com.riffle.core.domain.EbookFormat.Epub)
                ))
            override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkSeriesResult.Success(emptyList())
            override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkCollectionResult.Success(emptyList())
        }
        val result = makeRepo(libraryItemDao = itemDao, api = api, storytellerReadaloudSyncer = spySyncer)
            .refreshLibraryItems("lib-1")
        assertTrue(result is LibraryRefreshResult.Success)
        assertTrue("syncStale() should have been called during ABS library refresh", synced)
    }
}
