package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val collectionItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())

    private fun fakeRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = collectionItemsFlow
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
    }

    private val noOpServerRepo = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Server? = null
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
    }

    private val noOpTokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun deleteToken(serverId: String) {}
    }

    private class FakeEpubRepository(private val downloadedIds: Set<String> = emptySet()) : EpubRepository {
        override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem) = EpubDownloadResult.Success
        override suspend fun removeDownload(itemId: String) {}
        override fun isDownloaded(itemId: String): Boolean = itemId in downloadedIds
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakePdfRepository : PdfRepository {
        override suspend fun openPdf(item: LibraryItem) = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem) = PdfDownloadResult.Success
        override suspend fun removeDownload(itemId: String) {}
        override fun isDownloaded(itemId: String): Boolean = false
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private fun makeVm(
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
        epubRepository: EpubRepository = FakeEpubRepository(),
    ) = CollectionDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("collectionId" to "col-1", "libraryId" to "lib-1")),
        libraryRepository = fakeRepo(),
        serverRepository = noOpServerRepo,
        tokenStorage = noOpTokenStorage,
        epubRepository = epubRepository,
        pdfRepository = FakePdfRepository(),
        connectivityObserver = connectivityObserver,
    )

    private fun item(id: String) = LibraryItem(
        id = id, libraryId = "lib-1", title = id, author = "Author", coverUrl = null,
        readingProgress = 0f, isCached = false, isDownloaded = false, ebookFormat = EbookFormat.Epub,
    )

    @Test
    fun `when online all collection items are returned`() = runTest {
        val vm = makeVm(connectivityObserver = FakeConnectivityObserver(online = true))
        backgroundScope.launch { vm.items.collect {} }
        collectionItemsFlow.value = listOf(item("a"), item("b"), item("c"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.items.value.size)
        assertEquals(false, vm.isOffline.value)
    }

    @Test
    fun `when offline only locally available items are returned`() = runTest {
        val vm = makeVm(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = FakeEpubRepository(downloadedIds = setOf("a")),
        )
        backgroundScope.launch { vm.items.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        collectionItemsFlow.value = listOf(item("a"), item("b"), item("c"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("a")), vm.items.value)
        assertEquals(true, vm.isOffline.value)
    }

    @Test
    fun `when offline and no items are downloaded the list is empty`() = runTest {
        val vm = makeVm(connectivityObserver = FakeConnectivityObserver(online = false))
        backgroundScope.launch { vm.items.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        collectionItemsFlow.value = listOf(item("a"), item("b"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<LibraryItem>(), vm.items.value)
    }

    @Test
    fun `items refilter when connectivity changes from offline to online`() = runTest {
        val connectivity = FakeConnectivityObserver(online = false)
        val vm = makeVm(connectivityObserver = connectivity, epubRepository = FakeEpubRepository(setOf("a")))
        backgroundScope.launch { vm.items.collect {} }
        collectionItemsFlow.value = listOf(item("a"), item("b"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("a")), vm.items.value)

        connectivity.state.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("a"), item("b")), vm.items.value)
        assertEquals(false, vm.isOffline.value)
    }
}
