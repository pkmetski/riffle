package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
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
class SeriesDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val seriesItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())

    private fun countingRepo(
        refreshResult: () -> LibraryRefreshResult,
        onRefreshCall: () -> Unit = {},
    ): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(serverId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = seriesItemsFlow
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
            onRefreshCall(); return refreshResult()
        }
        override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
    }

    private val noOpServerRepo = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private val noOpTokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun deleteToken(serverId: String) {}
    }

    private class FakeEpubRepository : EpubRepository {
        override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) = EpubDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakePdfRepository : PdfRepository {
        override suspend fun openPdf(item: LibraryItem) = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit) = PdfDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private class FakeAudiobookDownloadRepository : AudiobookDownloadRepository {
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(serverId: String, itemId: String, onProgress: (Long, Long) -> Unit) =
            AudiobookDownloadResult.Success
        override suspend fun remove(serverId: String, itemId: String): Long = 0L
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private fun makeVm(
        libraryRepository: LibraryRepository,
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
    ) = SeriesDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("seriesId" to "ser-1", "libraryId" to "lib-1")),
        libraryRepository = libraryRepository,
        serverRepository = noOpServerRepo,
        tokenStorage = noOpTokenStorage,
        offlineAvailability = LibraryItemOfflineAvailability(
            FakeEpubRepository(),
            FakePdfRepository(),
            FakeAudiobookDownloadRepository(),
        ),
        connectivityObserver = connectivityObserver,
    )

    @Test
    fun `does not poll while refresh keeps succeeding`() = runTest {
        var refreshCount = 0
        val vm = makeVm(countingRepo({ LibraryRefreshResult.Success }) { refreshCount++ })
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        val baseline = refreshCount
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline, refreshCount)
    }

    @Test
    fun `polls every 10 seconds while refresh is failing`() = runTest {
        var refreshCount = 0
        var result: LibraryRefreshResult = LibraryRefreshResult.NetworkError(RuntimeException("boom"))
        val vm = makeVm(countingRepo({ result }) { refreshCount++ })
        backgroundScope.launch { vm.isOffline.collect {} }
        // advanceUntilIdle() would hang here — once _refreshFailed is true the polling
        // coroutine schedules an endless delay→refresh chain, so the scheduler is never idle.
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.isOffline.value)
        val baseline = refreshCount
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline + 1, refreshCount)
        testDispatcher.scheduler.advanceTimeBy(10_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline + 2, refreshCount)
        // Stop polling before runTest tears down — its scheduler-drain step would otherwise
        // chase the endless delay→refresh chain and time out.
        result = LibraryRefreshResult.Success
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `does not poll while device is offline`() = runTest {
        var refreshCount = 0
        val vm = makeVm(
            libraryRepository = countingRepo({ LibraryRefreshResult.NetworkError(RuntimeException("boom")) }) { refreshCount++ },
            connectivityObserver = FakeConnectivityObserver(online = false),
        )
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.isOffline.value)
        val baseline = refreshCount
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline, refreshCount)
    }

    @Test
    fun `polling stops once a retry succeeds`() = runTest {
        var refreshCount = 0
        var result: LibraryRefreshResult = LibraryRefreshResult.NetworkError(RuntimeException("boom"))
        val vm = makeVm(countingRepo({ result }) { refreshCount++ })
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.isOffline.value)

        result = LibraryRefreshResult.Success
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
        assertEquals(false, vm.isOffline.value)

        val countAfterRecovery = refreshCount
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(countAfterRecovery, refreshCount)
    }
}
