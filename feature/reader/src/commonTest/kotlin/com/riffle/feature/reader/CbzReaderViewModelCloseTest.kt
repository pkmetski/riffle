package com.riffle.feature.reader

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Library
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.BookComicFormattingPreferencesStore
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.panel.ColorPageDecoder
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelEngine
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.Series
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.SyncSessionResult
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.models.Collection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test: [CbzReaderViewModel.onReaderClosed] must flush the current reading position
 * via [ApplicationScope.launchSurvivable] so the save persists even when [viewModelScope] is
 * cancelled immediately after (which happens when Android destroys the ViewModel the instant the
 * user backs out of the reader — `onCleared()` calls `super.onCleared()` first, cancelling
 * `viewModelScope` before any in-flight `viewModelScope.launch` save can execute).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CbzReaderViewModelCloseTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `onReaderClosed flushes position via applicationScope`() = runTest(testDispatcher) {
        var appScopeLaunches = 0

        val fakeAppScope = object : ApplicationScope {
            private val inner = CoroutineScope(testDispatcher + SupervisorJob())
            override val coroutineScope: CoroutineScope = inner
            override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job {
                appScopeLaunches++
                return inner.launch(block = block)
            }
            override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T = block(inner)
        }

        val savedPositions = mutableListOf<Triple<String, String, String>>()
        val fakeCbzRepo = object : FakeNullCbzRepository() {
            override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Success(
                imageSource = object : ComicPageSource {
                    override val pageCount = 5
                    override fun imageBytes(pageIndex: Int) = ByteArray(0)
                    override fun mediaType(pageIndex: Int) = "image/jpeg"
                },
                pageCount = 5,
                lastPosition = null,
            )
            override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {
                savedPositions += Triple(sourceId, itemId, locatorJson)
            }
        }

        val fakeItem = LibraryItem(
            id = "item1", libraryId = "lib1", title = "Comic", author = "Author",
            coverUrl = null, readingProgress = 0f, isCached = true, isDownloaded = true,
            ebookFormat = EbookFormat.Cbz, sourceId = "src1", pageCount = 5,
        )

        val vm = CbzReaderViewModel(
            itemId = "item1",
            sourceId = "src1",
            libraryObserver = FakeLibraryObserver(fakeItem),
            cbzRepository = fakeCbzRepo,
            readingSessionRepository = FakeReadingSessionRepository(),
            updateReadingProgressUseCase = object : UpdateReadingProgress(object : LibraryMutator {
                override suspend fun markItemOpened(itemId: String) {}
                override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
                override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
                override suspend fun deleteItem(sourceId: String, itemId: String) {}
            }) {},
            wakeLockPreferencesStore = FakeWakeLockStore(),
            volumeNavigationController = VolumeNavigationController(),
            volumeKeyDispatcher = VolumeKeyDispatcher(FakeVolumeKeyPreferencesStore(), VolumeNavigationController()),
            readerStateHolder = ReaderStateHolder(),
            panelEngine = FakePanelEngine(),
            panelMaskService = FakePanelMaskService(),
            panelViewPreferencesStore = FakePanelViewPreferencesStore(),
            comicFormattingPreferencesStore = FakeComicFormattingStore(),
            bookComicFormattingPreferencesStore = FakeBookComicFormattingStore(),
            developerOptionsRepository = FakeDeveloperOptionsRepository(),
            appearanceCoordinator = FakeAppearanceCoordinator(),
            colorPageDecoder = FakeColorPageDecoder(),
            dispatchers = FakeDispatcherProvider(testDispatcher),
            panelReportRepository = FakePanelReportRepository(),
            applicationScope = fakeAppScope,
        )

        // Pump init coroutines: viewModelScope.launch { openBook() } sets state to Ready
        runCurrent()

        vm.onReaderClosed()
        // Pump the applicationScope coroutine launched by onReaderClosed
        runCurrent()

        assertEquals(
            1, appScopeLaunches,
            "onReaderClosed must use applicationScope to survive viewModelScope cancellation"
        )
        assertEquals(1, savedPositions.size, "saveReadingPosition must be called once")
        assertEquals("src1", savedPositions.first().first)
        assertEquals("item1", savedPositions.first().second)
    }
}

// ---- Minimal fakes ----

private abstract class FakeNullCbzRepository : CbzRepository {
    override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Offline
    override suspend fun downloadCbz(item: LibraryItem, onProgress: (Long, Long) -> Unit): CbzDownloadResult = CbzDownloadResult.AlreadyDownloaded
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override fun isDownloaded(sourceId: String, itemId: String) = false
    override fun isCached(sourceId: String, itemId: String) = false
    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {}
    override suspend fun supportsStreaming(sourceId: String) = false
    override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?) = ByteArray(0)
    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
}

private class FakeLibraryObserver(private val item: LibraryItem) : LibraryObserver {
    override fun observeLibraries() = flowOf(emptyList<Library>())
    override fun observeLibraries(sourceId: String) = flowOf(emptyList<Library>())
    override fun observeLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeUngroupedLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeInProgressItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeFinishedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeRecentlyAddedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeAllBooks(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeSeries(libraryId: String) = flowOf(emptyList<Series>())
    override fun observeCollections(libraryId: String) = flowOf(emptyList<Collection>())
    override fun observeSeriesItems(seriesId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeContinueSeriesItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeCollectionItems(collectionId: String) = flowOf(emptyList<LibraryItem>())
    override suspend fun getItem(itemId: String): LibraryItem = item
    override fun observeItem(itemId: String) = flowOf(item)
    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem = item
    override suspend fun getLibrary(libraryId: String) = null
    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String) = null
}

private class FakeReadingSessionRepository : ReadingSessionRepository {
    override suspend fun syncProgress(itemId: String, payload: SessionPayload) = SyncSessionResult.Success
    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload) = ProgressSyncCycleResult.InSync
    override suspend fun markFinished(itemId: String, finished: Boolean) {}
    override suspend fun touchOpenTimestamp(itemId: String) {}
}

private class FakeWakeLockStore : WakeLockPreferencesStore {
    override val keepScreenOn: Flow<Boolean> = flowOf(true)
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
}

private class FakeVolumeKeyPreferencesStore : VolumeKeyPreferencesStore {
    override val volumeKeyNavigationEnabled: Flow<Boolean> = flowOf(true)
    override val invertVolumeKeys: Flow<Boolean> = flowOf(false)
    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) {}
    override suspend fun setInvertVolumeKeys(value: Boolean) {}
}

private class FakePanelEngine : PanelEngine {
    override fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): PanelEngine.Book =
        object : PanelEngine.Book {
            override fun resolvePage(pageIndex: Int) = PagePanels(
                pageIndex = pageIndex, imageWidth = 100, imageHeight = 200,
                panels = emptyList(), source = PanelSource.Fallback,
            )
        }
}

private class FakePanelMaskService : PanelMaskService {
    override suspend fun generateMask(pageIndex: Int, rawImageBytes: ByteArray) = null
}

private class FakePanelViewPreferencesStore : PanelViewPreferencesStore {
    override fun state(bookId: String) = flowOf(PanelViewPreferencesStore.State(false))
    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {}
}

private class FakeComicFormattingStore : ComicFormattingPreferencesStore {
    override val preferences = flowOf(ComicFormattingPreferences())
    override suspend fun update(prefs: ComicFormattingPreferences) {}
}

private class FakeBookComicFormattingStore : BookComicFormattingPreferencesStore {
    override fun overrides(bookId: String) = flowOf(BookComicFormattingOverrides())
    override suspend fun save(bookId: String, overrides: BookComicFormattingOverrides) {}
    override suspend fun reset(bookId: String) {}
}

private class FakeDeveloperOptionsRepository : DeveloperOptionsRepository {
    override val developerModeEnabled: Flow<Boolean> = flowOf(false)
    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {}
    override suspend fun getGithubPat() = null
    override suspend fun setGithubPat(pat: String?) {}
}

private class FakeAppearanceCoordinator : AppearanceCoordinator {
    override val resolved: StateFlow<ResolvedAppearance> = MutableStateFlow(
        ResolvedAppearance(ChromeTheme.Light, ConcreteReaderTheme.Light, false)
    )
    override fun setSystemDark(isDark: Boolean) {}
}

private class FakeColorPageDecoder : ColorPageDecoder {
    override fun decode(bytes: ByteArray, targetLongEdge: Int) = null
}

private class FakeDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val mainImmediate = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}

private class FakePanelReportRepository : PanelReportRepository {
    override suspend fun submit(
        report: com.riffle.core.domain.comic.panel.PanelDetectionReport,
        maskPng: ByteArray,
    ) = Result.failure<String>(UnsupportedOperationException("fake"))
}
