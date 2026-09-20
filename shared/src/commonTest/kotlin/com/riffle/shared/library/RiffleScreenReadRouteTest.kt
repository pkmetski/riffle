package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.feature.library.FetchAudiobookChaptersUseCase
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.RiffleViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Regression for the Riffle hub's dead "Read" button and its unclickable annotated-book rows
 * (#1071 §10).
 *
 * The hub used to pass `onReadNotSupported = { selectedItem = null }` into the detail sheet, so
 * tapping Read dismissed the sheet instead of opening the book — the per-library host next door
 * routed the same button through `readerNavForItem`. And the Annotations tab rendered its rows
 * with no click handler at all, so an annotated book could not be opened from the hub.
 *
 * The book here is [EbookFormat.Unsupported] on purpose: `readerNavForItem` returns null for it,
 * so the assertions can observe "the sheet stayed open" without needing a reader's Koin graph.
 * Under the old wiring the very same tap returned to the tab list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RiffleScreenReadRouteTest {

    private val hubItem = LibraryItem(
        id = "item1",
        libraryId = "lib1",
        title = "Hub Book",
        author = "Hub Author",
        coverUrl = null,
        readingProgress = 0.3f,
        isCached = false,
        isDownloaded = false,
        // No iOS reader for this format, so readerNavForItem() returns null and the sheet must
        // simply stay put. Any dismissal is the bug.
        ebookFormat = EbookFormat.Unsupported,
        sourceId = "source1",
    )

    private val annotatedBook = AnnotatedBook(
        sourceId = "source1",
        itemId = "item1",
        title = "Annotated Title",
        author = "Hub Author",
        coverUrl = null,
        highlightCount = 2,
        latestUpdatedAt = 0L,
    )

    private val libraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItemsAllSources(): Flow<List<LibraryItem>> = flowOf(listOf(hubItem))
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem = hubItem
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(hubItem)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem = hubItem
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = flowOf(hubItem)
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private val annotationsRepository = object : AnnotationsLibraryRepository {
        override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(listOf(annotatedBook))
        override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> =
            flowOf(listOf(annotatedBook))
        override fun observeAnnotatedBooksAllSources(): Flow<List<AnnotatedBook>> = flowOf(listOf(annotatedBook))
    }

    private val sourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException("test fake"))
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
        override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = SyncNamespace.LocalOnly("test")
    }

    private val tokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private val toReadRepository = object : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun refresh(libraryId: String): Boolean = true
        override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = true
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private val connectivityObserver = object : ConnectivityObserver {
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private val offlineAvailability = object : LibraryItemOfflineAvailability {
        override fun isAvailableOffline(item: LibraryItem): Boolean = true
    }

    private fun detailViewModel(itemId: String, sourceId: String?) = LibraryItemDetailViewModel(
        itemId = itemId,
        sourceId = sourceId,
        libraryObserver = libraryObserver,
        recordItemOpened = FakeRecordItemOpened(),
        updateReadingProgressUseCase = FakeUpdateReadingProgress(),
        markReadAcrossDimensions = FakeMarkReadAcrossDimensions(),
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        epubRepository = FakeEpubRepository(),
        ebookCfiTranslatorFactory = FakeEbookCfiTranslatorFactory,
        audiobookPositionStore = FakeAudiobookPositionStore(),
        pdfRepository = FakePdfRepository(),
        cbzRepository = FakeCbzRepository(),
        toReadRepository = toReadRepository,
        playlistsRepository = FakePlaylistsRepository(),
        readaloudLinkRepository = FakeReadaloudLinkRepository,
        readaloudAudioRepository = FakeReadaloudAudioRepository(),
        audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
        audiobookCacheRepository = FakeAudiobookCacheRepository(),
        localAvailabilityEvents = FakeLocalAvailabilityEvents(),
        readaloudOfflineDownloader = FakeReadaloudOfflineDownloader,
        connectivityObserver = connectivityObserver,
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
    )

    @BeforeTest
    fun setUp() {
        // Both view models launch on viewModelScope (Dispatchers.Main). Unconfined makes the init
        // work run inline during composition so the sheet reaches Ready deterministically.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        startKoin {
            modules(
                module {
                    single {
                        RiffleViewModel(
                            libraryObserver = libraryObserver,
                            sourceRepository = sourceRepository,
                            tokenStorage = tokenStorage,
                            toReadRepository = toReadRepository,
                            annotationsLibraryRepository = annotationsRepository,
                            connectivityObserver = connectivityObserver,
                            offlineAvailability = offlineAvailability,
                        )
                    }
                    factory { params -> detailViewModel(params.get(), params.getOrNull()) }
                    // RiffleScreen resolves both of these to record the open when Read routes to
                    // a reader (#1071 §17). Mechanical fixture update for the new dependency —
                    // the recording itself is pinned by OpenItemForReadingTest.
                    single<ApplicationScope> { DefaultApplicationScope(CoroutineScope(UnconfinedTestDispatcher())) }
                    single<RecordItemOpened> { FakeRecordItemOpened() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAnInProgressRowOpensTheDetailSheet() = runComposeUiTest {
        setContent { RiffleScreen(onOpenDrawer = {}, onBack = {}) }

        onNodeWithText("Hub Book").performClick()

        onNodeWithText("Read").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingReadDoesNotDismissTheDetailSheet() = runComposeUiTest {
        setContent { RiffleScreen(onOpenDrawer = {}, onBack = {}) }

        onNodeWithText("Hub Book").performClick()
        onNodeWithText("Read").performClick()

        // The old wiring set `selectedItem = null` here, dropping straight back to the tab list.
        onNodeWithText("Read").assertIsDisplayed()
        onNodeWithText("In Progress").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAnAnnotatedBookOpensItsDetailSheet() = runComposeUiTest {
        setContent { RiffleScreen(onOpenDrawer = {}, onBack = {}) }

        onNodeWithText("Annotations").performClick()
        onNodeWithText("Annotated Title").performClick()

        onNodeWithText("Read").assertIsDisplayed()
    }
}
