package com.riffle.feature.library

import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.models.Collection
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The failed-refresh polling loop: once a refresh reports a network error, the ViewModel retries
 * every [10s] while online, and stops the instant a retry succeeds or the device goes offline.
 * Ported from the former :app SeriesDetailViewModelTest so this shared-VM behaviour is verified on
 * iOS too (see docs/testing/android-ios-parity-audit.md). Uses a scheduler-controlled Main so the
 * delay→refresh chain advances deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModelPollingTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class CountingRefreshSeries(
        private val refreshResult: () -> LibraryRefreshResult,
        private val onCall: () -> Unit = {},
    ) : RefreshSeries(NoopLibraryRefresher) {
        override suspend fun invoke(libraryId: String): LibraryRefreshResult {
            onCall(); return refreshResult()
        }
    }

    private object NoopLibraryRefresher : LibraryRefresher {
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private fun fakeObserver(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private val noOpSourceRepo = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>): com.riffle.core.domain.CommitSourceResult =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val noOpTokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val neverOfflineAvailable = object : LibraryItemOfflineAvailability {
        override fun isAvailableOffline(item: LibraryItem): Boolean = false
    }

    private fun makeVm(
        refreshSeriesUseCase: RefreshSeries = CountingRefreshSeries({ LibraryRefreshResult.Success }),
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
    ) = SeriesDetailViewModel(
        seriesId = "ser-1",
        libraryId = "lib-1",
        libraryObserver = fakeObserver(),
        refreshSeriesUseCase = refreshSeriesUseCase,
        sourceRepository = noOpSourceRepo,
        tokenStorage = noOpTokenStorage,
        offlineAvailability = neverOfflineAvailable,
        connectivityObserver = connectivityObserver,
    )

    @Test
    fun `does not poll while refresh keeps succeeding`() = runTest {
        var refreshCount = 0
        val vm = makeVm(CountingRefreshSeries({ LibraryRefreshResult.Success }) { refreshCount++ })
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
        val vm = makeVm(CountingRefreshSeries({ result }) { refreshCount++ })
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.isOffline.value)
        val baseline = refreshCount
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline + 1, refreshCount)
        testDispatcher.scheduler.advanceTimeBy(10_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline + 2, refreshCount)
        // Stop polling before runTest tears down its scheduler.
        result = LibraryRefreshResult.Success
        testDispatcher.scheduler.advanceTimeBy(10_001)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `does not poll while device is offline`() = runTest {
        var refreshCount = 0
        val vm = makeVm(
            refreshSeriesUseCase = CountingRefreshSeries({ LibraryRefreshResult.NetworkError(RuntimeException("boom")) }) { refreshCount++ },
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
        val vm = makeVm(CountingRefreshSeries({ result }) { refreshCount++ })
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
