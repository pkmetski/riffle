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
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
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

    private fun fakeRepo(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = seriesItemsFlow
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private class CountingRefreshSeries(
        val refreshResult: () -> LibraryRefreshResult,
        val onCall: () -> Unit = {},
    ) : com.riffle.core.domain.usecase.RefreshSeries(com.riffle.app.testing.NoopLibraryRefresher) {
        override suspend fun invoke(libraryId: String): LibraryRefreshResult {
            onCall(); return refreshResult()
        }
    }

    private val noOpServerRepo = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType, sourceType: com.riffle.core.domain.SourceType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
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

    private class FakeEpubRepository : EpubRepository {
        override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) = EpubDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakePdfRepository : PdfRepository {
        override suspend fun openPdf(item: LibraryItem) = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit) = PdfDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private class FakeAudiobookDownloadRepository : AudiobookDownloadRepository {
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(sourceId: String, itemId: String, onProgress: (Long, Long) -> Unit) =
            AudiobookDownloadResult.Success
        override suspend fun remove(sourceId: String, itemId: String): Long = 0L
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private fun makeVm(
        refreshSeriesUseCase: com.riffle.core.domain.usecase.RefreshSeries =
            CountingRefreshSeries({ LibraryRefreshResult.Success }),
        libraryObserver: LibraryObserver = fakeRepo(),
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
    ) = SeriesDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("seriesId" to "ser-1", "libraryId" to "lib-1")),
        libraryObserver = libraryObserver,
        refreshSeriesUseCase = refreshSeriesUseCase,
        sourceRepository = noOpServerRepo,
        tokenStorage = noOpTokenStorage,
        offlineAvailability = LibraryItemOfflineAvailability(
            FakeEpubRepository(),
            FakePdfRepository(),
            NoopCbzRepository(),
            FakeAudiobookDownloadRepository(),
            object : BundleAudiobookSource {
                override suspend fun localSession(sourceId: String, itemId: String) = null
                override fun isAvailableOffline(sourceId: String, itemId: String) = false
            },
        ),
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
