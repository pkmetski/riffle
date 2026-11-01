package com.riffle.shared.library

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import com.riffle.feature.library.BookImportState
import com.riffle.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
        override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = true
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

    private fun makeActiveSourceRepository(sourceId: String = "source1") = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = Source(
            id = sourceId,
            url = SourceUrl.parse("http://test-server")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "test",
        )
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
        override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = SyncNamespace.LocalOnly("test")
    }

    private fun makeLinkReturningRepository(link: ReadaloudLink) = object : ReadaloudLinkRepository {
        override fun observeAll(): kotlinx.coroutines.flow.Flow<List<ReadaloudLink>> = flowOf(emptyList())
        override fun observeLinkedAbsItemIds(): kotlinx.coroutines.flow.Flow<Set<String>> = flowOf(emptySet())
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink = link
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
        override suspend fun countForSource(sourceId: String): Int = 0
        override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: com.riffle.core.models.AudiobookIdentityResult) {}
    }

    private fun makeViewModel(
        item: LibraryItem? = fakeItem,
        itemId: String = "item1",
        sourceId: String? = "source1",
        inToRead: Boolean = false,
        bookImportManager: com.riffle.feature.library.BookImportManager = FakeBookImportManager(),
        sourceRepository: SourceRepository = makeSourceRepository(),
        readaloudLinkRepository: ReadaloudLinkRepository = FakeReadaloudLinkRepository,
    ) = LibraryItemDetailViewModel(
        itemId = itemId,
        sourceId = sourceId,
        libraryObserver = makeLibraryObserver(item),
        recordItemOpened = FakeRecordItemOpened(),
        updateReadingProgressUseCase = FakeUpdateReadingProgress(),
        markReadAcrossDimensions = FakeMarkReadAcrossDimensions(),
        sourceRepository = sourceRepository,
        tokenStorage = makeTokenStorage(),
        epubRepository = FakeEpubRepository(),
        ebookCfiTranslatorFactory = FakeEbookCfiTranslatorFactory,
        audiobookPositionStore = FakeAudiobookPositionStore(),
        pdfRepository = FakePdfRepository(),
        cbzRepository = FakeCbzRepository(),
        toReadRepository = makeToReadRepository(inToRead),
        playlistsRepository = FakePlaylistsRepository(),
        readaloudLinkRepository = readaloudLinkRepository,
        readaloudAudioRepository = FakeReadaloudAudioRepository(),
        audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
        audiobookCacheRepository = FakeAudiobookCacheRepository(),
        localAvailabilityEvents = FakeLocalAvailabilityEvents(),
        readaloudOfflineDownloader = FakeReadaloudOfflineDownloader,
        connectivityObserver = makeConnectivityObserver(),
        downloadManager = FakeDownloadManager(),
        bookImportManager = bookImportManager,
        crossEpubIndexBuildTrigger = FakeCrossEpubIndexBuildTrigger,
        sidecarPrefetcher = FakeReadaloudSidecarPrefetcher,
        epubTocExtractor = FakeEpubTocExtractor(),
        pdfPageCountExtractor = FakePdfPageCountExtractor,
        fetchAudiobookChaptersUseCase = FetchAudiobookChaptersUseCase(FakeAudiobookChapterCacheRepository()),
        catalogRegistry = FakeCatalogRegistry,
        libraryRefresher = FakeLibraryRefresher(),
        saveLocalFileMetadataOverride = FakeLocalFileMetadataOverrideSaver,
        copyCoverImage = FakeCoverImageCopier,
        readingSpeedStore = FakeReadingSpeedStore(),
        webSourceLibraryItemUpserter = FakeWebSourceLibraryItemUpserter,
        dispatchers = object : com.riffle.core.domain.DispatcherProvider {
            private val d = mainDispatcher
            override val main = d; override val mainImmediate = d; override val io = d; override val default = d
        },
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
        assertFalse(ready.isInToRead, "Expected isInToRead=false before toggle")

        vm.toggleToRead()
        testScheduler.advanceUntilIdle()

        val toggled = vm.uiState.value as LibraryItemDetailUiState.Ready
        assertTrue(toggled.isInToRead, "Expected isInToRead=true after toggle")
    }

    @Test
    fun bookImportFailureEmitsSnackbar() = runTest(mainDispatcher) {
        val importManager = FakeBookImportManager()
        val vm = makeViewModel(bookImportManager = importManager)
        testScheduler.advanceUntilIdle()

        val snackbars = mutableListOf<String>()
        val job = launch { vm.snackbarEvents.collect { snackbars.add(it) } }

        // key must start with "import:source1:item1:" — the prefix importKeyPrefix(fakeItem) uses
        importManager.emit("import:source1:item1:dest:lib", BookImportState.Failed("Upload failed"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Upload failed"), snackbars)
        job.cancel()
    }

    // Regression: when sourceId is null (browse navigation for web-source items like Chitanka),
    // markAsRead must use the item's own sourceId from the loaded state, not the VM constructor
    // param. The iOS Koin factory used to resolve both String params to the first value (itemId)
    // via type-based matching, causing a FK violation on the reading_positions insert.
    @Test
    fun markAsReadWithNullSourceIdUsesItemSourceId() = runTest(mainDispatcher) {
        val recordingMarkRead = RecordingMarkReadAcrossDimensions()
        val vmDirect = LibraryItemDetailViewModel(
            itemId = fakeItem.id,
            sourceId = null,
            libraryObserver = makeLibraryObserver(fakeItem),
            recordItemOpened = FakeRecordItemOpened(),
            updateReadingProgressUseCase = FakeUpdateReadingProgress(),
            markReadAcrossDimensions = recordingMarkRead,
            sourceRepository = makeSourceRepository(),
            tokenStorage = makeTokenStorage(),
            epubRepository = FakeEpubRepository(),
            ebookCfiTranslatorFactory = FakeEbookCfiTranslatorFactory,
            audiobookPositionStore = FakeAudiobookPositionStore(),
            pdfRepository = FakePdfRepository(),
            cbzRepository = FakeCbzRepository(),
            toReadRepository = makeToReadRepository(false),
            playlistsRepository = FakePlaylistsRepository(),
            readaloudLinkRepository = FakeReadaloudLinkRepository,
            readaloudAudioRepository = FakeReadaloudAudioRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            audiobookCacheRepository = FakeAudiobookCacheRepository(),
            localAvailabilityEvents = FakeLocalAvailabilityEvents(),
            readaloudOfflineDownloader = FakeReadaloudOfflineDownloader,
            connectivityObserver = makeConnectivityObserver(),
            downloadManager = FakeDownloadManager(),
            bookImportManager = FakeBookImportManager(),
            crossEpubIndexBuildTrigger = FakeCrossEpubIndexBuildTrigger,
            sidecarPrefetcher = FakeReadaloudSidecarPrefetcher,
            epubTocExtractor = FakeEpubTocExtractor(),
            pdfPageCountExtractor = FakePdfPageCountExtractor,
            fetchAudiobookChaptersUseCase = FetchAudiobookChaptersUseCase(FakeAudiobookChapterCacheRepository()),
            catalogRegistry = FakeCatalogRegistry,
            libraryRefresher = FakeLibraryRefresher(),
            saveLocalFileMetadataOverride = FakeLocalFileMetadataOverrideSaver,
            copyCoverImage = FakeCoverImageCopier,
            readingSpeedStore = FakeReadingSpeedStore(),
            webSourceLibraryItemUpserter = FakeWebSourceLibraryItemUpserter,
            dispatchers = object : com.riffle.core.domain.DispatcherProvider {
                private val d = mainDispatcher
                override val main = d; override val mainImmediate = d; override val io = d; override val default = d
            },
        )
        testScheduler.advanceUntilIdle()
        assertIs<LibraryItemDetailUiState.Ready>(vmDirect.uiState.value)

        vmDirect.markAsRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1, recordingMarkRead.calls.size, "markAsRead must invoke MarkReadAcrossDimensions exactly once")
        val (usedSourceId, usedItemId, finished) = recordingMarkRead.calls.first()
        assertEquals(
            fakeItem.sourceId,
            usedSourceId,
            "sourceId passed to MarkReadAcrossDimensions must be the item's sourceId, not the VM constructor's null param",
        )
        assertEquals(fakeItem.id, usedItemId)
        assertTrue(finished)
    }

    @Test
    fun capabilitiesHasReadaloudFalseWhenNoLink() = runTest(mainDispatcher) {
        val vm = makeViewModel(
            sourceRepository = makeActiveSourceRepository(),
            readaloudLinkRepository = FakeReadaloudLinkRepository,
        )
        testScheduler.advanceUntilIdle()
        val ready = vm.uiState.value as LibraryItemDetailUiState.Ready
        assertFalse(
            ready.capabilities.hasReadaloud,
            "hasReadaloud must be false when the item has no readaloud link",
        )
    }

    @Test
    fun capabilitiesHasReadaloudTrueWhenLinkExists() = runTest(mainDispatcher) {
        val link = ReadaloudLink(
            storytellerSourceId = "st-source",
            storytellerBookId = "st-book",
            absSourceId = "source1",
            absLibraryItemId = "item1",
            userConfirmed = false,
        )
        val vm = makeViewModel(
            sourceRepository = makeActiveSourceRepository(),
            readaloudLinkRepository = makeLinkReturningRepository(link),
        )
        testScheduler.advanceUntilIdle()
        val ready = vm.uiState.value as LibraryItemDetailUiState.Ready
        assertTrue(
            ready.capabilities.hasReadaloud,
            "hasReadaloud must be true when the item has a readaloud link",
        )
    }
}
