package com.riffle.shared.library

import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalNativeApi::class, ExperimentalCoroutinesApi::class)
class LibraryItemDetailViewModelTest {

    // viewModelScope launches on Dispatchers.Main; back it with a scheduler-controlled test
    // dispatcher so the init coroutine is *deferred* (letting us observe the Loading state) and
    // advanced deterministically via testScheduler.advanceUntilIdle().
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private val fakeItem = LibraryItem(
        id = "item1",
        libraryId = "lib1",
        title = "Test Book",
        author = "Test Author",
        coverUrl = null,
        readingProgress = 0.0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = "source1",
    )

    private fun makeLibraryObserver(item: LibraryItem? = fakeItem) = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = item
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(item)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = item
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = flowOf(item)
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun makeToReadRepository(inToRead: Boolean = false) = object : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun refresh(libraryId: String): Boolean = false
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = inToRead
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private fun makeConnectivityObserver(online: Boolean = true) = object : ConnectivityObserver {
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(online)
    }

    private fun makeSourceRepository() = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
        override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = SyncNamespace.LocalOnly("test")
    }

    private fun makeTokenStorage() = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private fun makeViewModel(
        item: LibraryItem? = fakeItem,
        itemId: String = "item1",
        sourceId: String? = "source1",
        inToRead: Boolean = false,
    ) = LibraryItemDetailViewModel(
        itemId = itemId,
        sourceId = sourceId,
        libraryObserver = makeLibraryObserver(item),
        recordItemOpened = IosNoOpRecordItemOpened(),
        updateReadingProgressUseCase = IosNoOpUpdateReadingProgress(),
        markReadAcrossDimensions = IosNoOpMarkReadAcrossDimensions(),
        sourceRepository = makeSourceRepository(),
        tokenStorage = makeTokenStorage(),
        epubRepository = IosNoOpEpubRepository(),
        ebookCfiTranslatorFactory = IosNoOpEbookCfiTranslatorFactory,
        audiobookPositionStore = IosNoOpAudiobookPositionStore(),
        pdfRepository = IosNoOpPdfRepository(),
        cbzRepository = IosNoOpCbzRepository(),
        toReadRepository = makeToReadRepository(inToRead),
        playlistsRepository = IosNoOpPlaylistsRepository(),
        readaloudLinkRepository = IosNoOpReadaloudLinkRepository(),
        readaloudAudioRepository = IosNoOpReadaloudAudioRepository(),
        audiobookDownloadRepository = IosNoOpAudiobookDownloadRepository(),
        audiobookCacheRepository = IosNoOpAudiobookCacheRepository(),
        localAvailabilityEvents = IosNoOpLocalAvailabilityEvents(),
        readaloudOfflineDownloader = IosNoOpReadaloudOfflineDownloader,
        connectivityObserver = makeConnectivityObserver(),
        downloadManager = IosNoOpDownloadManager(),
        bookImportManager = IosNoOpBookImportManager(),
        crossEpubIndexBuildTrigger = IosNoOpCrossEpubIndexBuildTrigger,
        sidecarPrefetcher = IosNoOpReadaloudSidecarPrefetcher,
        epubTocExtractor = IosNoOpEpubTocExtractor(),
        pdfPageCountExtractor = IosNoOpPdfPageCountExtractor,
        fetchAudiobookChaptersUseCase = FetchAudiobookChaptersUseCase(IosNoOpAudiobookChapterCacheRepository()),
        catalogRegistry = IosNoOpCatalogRegistry,
        libraryRefresher = IosNoOpLibraryRefresher(),
        saveLocalFileMetadataOverride = IosNoOpLocalFileMetadataOverrideSaver,
        copyCoverImage = IosNoOpCoverImageCopier,
        readingSpeedStore = IosNoOpReadingSpeedStore(),
        webSourceLibraryItemUpserter = IosNoOpWebSourceLibraryItemUpserter,
    )

    @Test
    fun initialStateIsLoading() = runTest(mainDispatcher) {
        val vm = makeViewModel()
        // Loading is the synchronous initial value before the coroutine runs
        assertIs<LibraryItemDetailUiState.Loading>(vm.uiState.value)
    }

    @Test
    fun stateBecomesReadyWhenItemFound() = runTest(mainDispatcher) {
        val vm = makeViewModel(item = fakeItem)
        testScheduler.advanceUntilIdle()
        assertIs<LibraryItemDetailUiState.Ready>(vm.uiState.value)
    }

    @Test
    fun stateBecomesErrorWhenItemNotFound() = runTest(mainDispatcher) {
        val vm = makeViewModel(item = null, itemId = "missing")
        testScheduler.advanceUntilIdle()
        assertIs<LibraryItemDetailUiState.Error>(vm.uiState.value)
    }

    @Test
    fun toggleToReadFlipsIsInToRead() = runTest(mainDispatcher) {
        val vm = makeViewModel(inToRead = false)
        testScheduler.advanceUntilIdle()
        val ready = vm.uiState.value as LibraryItemDetailUiState.Ready
        assert(!ready.isInToRead) { "Expected isInToRead=false before toggle" }

        vm.toggleToRead()
        testScheduler.advanceUntilIdle()

        val toggled = vm.uiState.value as LibraryItemDetailUiState.Ready
        assert(toggled.isInToRead) { "Expected isInToRead=true after toggle" }
    }
}
