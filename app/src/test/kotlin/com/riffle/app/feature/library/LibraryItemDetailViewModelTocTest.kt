package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.Collection
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TocEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemDetailViewModelTocTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // --- fakes reused from LibraryItemDetailViewModelTest pattern ---

    private fun fakeRepo(item: LibraryItem?): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(serverId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = item
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow(item)
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private class FakeConnectivityObserver : ConnectivityObserver {
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private val noOpServerRepo = object : ServerRepository {
        override fun observeAll(): Flow<List<com.riffle.core.domain.Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): com.riffle.core.domain.Server? = null
        override suspend fun authenticate(
            url: com.riffle.core.domain.ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ): com.riffle.core.domain.AuthenticateResult = com.riffle.core.domain.AuthenticateResult.WrongCredentials()
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingServer,
            hiddenLibraryIds: Set<String>,
        ): com.riffle.core.domain.CommitServerResult = com.riffle.core.domain.CommitServerResult.Failure(Exception())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private val noOpTokenStorage = object : com.riffle.core.domain.TokenStorage {
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun deleteToken(serverId: String) {}
    }

    private val noOpToReadRepository = object : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun refresh(libraryId: String): Boolean = true
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private class FakeEpubRepo : EpubRepository {
        override suspend fun openEpub(item: LibraryItem): com.riffle.core.domain.EpubOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit): com.riffle.core.domain.EpubDownloadResult = com.riffle.core.domain.EpubDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakePdfRepo : PdfRepository {
        override suspend fun openPdf(item: LibraryItem): com.riffle.core.domain.PdfOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit): com.riffle.core.domain.PdfDownloadResult = com.riffle.core.domain.PdfDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private val noOpSessionRepo = object : ReadingSessionRepository {
        override suspend fun syncProgress(itemId: String, payload: com.riffle.core.domain.SessionPayload): com.riffle.core.domain.SyncSessionResult = com.riffle.core.domain.SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: com.riffle.core.domain.SessionPayload): com.riffle.core.domain.ProgressSyncCycleResult = com.riffle.core.domain.ProgressSyncCycleResult.InSync
        override suspend fun markFinished(itemId: String, finished: Boolean) {}
        override suspend fun touchOpenTimestamp(itemId: String) {}
    }

    private fun noOpExtractUseCase(): ExtractEpubTocUseCase = mockk<ExtractEpubTocUseCase>().also { uc ->
        coEvery { uc(any<LibraryItem>()) } returns emptyList<TocEntry>()
    }

    private fun noOpFetchUseCase(): FetchAudiobookChaptersUseCase = mockk<FetchAudiobookChaptersUseCase>().also { uc ->
        coEvery { uc(any<LibraryItem>()) } returns emptyList<AudiobookChapter>()
    }

    private fun makeVm(
        item: LibraryItem?,
        extractEpubTocUseCase: ExtractEpubTocUseCase = noOpExtractUseCase(),
        fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase = noOpFetchUseCase(),
    ) = LibraryItemDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("itemId" to (item?.id ?: "item-1"))),
        repository = fakeRepo(item),
        serverRepository = noOpServerRepo,
        tokenStorage = noOpTokenStorage,
        epubRepository = FakeEpubRepo(),
        pdfRepository = FakePdfRepo(),
        sessionRepository = noOpSessionRepo,
        toReadRepository = noOpToReadRepository,
        readaloudLinkRepository = NoopReadaloudLinkRepository,
        readaloudAudioRepository = NoopReadaloudAudioRepository,
        audiobookDownloadRepository = NoopAudiobookDownloadRepository,
        connectivityObserver = FakeConnectivityObserver(),
        downloadManager = DownloadManager(kotlinx.coroutines.CoroutineScope(testDispatcher)),
        crossEpubIndexBuildTrigger = object : com.riffle.core.data.CrossEpubIndexBuildTrigger {
            override fun enqueueBuild(link: com.riffle.core.domain.ReadaloudLink) {}
        },
        extractEpubTocUseCase = extractEpubTocUseCase,
        fetchAudiobookChaptersUseCase = fetchAudiobookChaptersUseCase,
    )

    private val epubItem = LibraryItem(
        id = "item-epub",
        libraryId = "lib-1",
        title = "Dune",
        author = "Frank Herbert",
        coverUrl = null,
        readingProgress = 0.0f,
        isCached = true,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        hasAudio = false,
        serverId = "srv-1",
    )

    private val audiobookItem = LibraryItem(
        id = "item-audio",
        libraryId = "lib-1",
        title = "Dune (Audio)",
        author = "Frank Herbert",
        coverUrl = null,
        readingProgress = 0.0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported,
        hasAudio = true,
        serverId = "srv-1",
    )

    private val audioOnlyItem = audiobookItem // alias for readability

    // --- tests ---

    @Test
    fun `tocState transitions to Ready with entries for EPUB item`() = runTest {
        val entries = listOf(TocEntry("Chapter 1", "ch1.html"), TocEntry("Chapter 2", "ch2.html"))
        val extractUseCase = mockk<ExtractEpubTocUseCase>().also { uc ->
            coEvery { uc(any<LibraryItem>()) } returns entries
        }

        val vm = makeVm(item = epubItem, extractEpubTocUseCase = extractUseCase)
        backgroundScope.launch { vm.tocState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TocState.Ready(entries), vm.tocState.value)
    }

    @Test
    fun `chaptersState transitions to Ready for audiobook item`() = runTest {
        val chapters = listOf(
            AudiobookChapter(index = 0, startSec = 0.0, endSec = 300.0, title = "Prologue"),
            AudiobookChapter(index = 1, startSec = 300.0, endSec = 900.0, title = "Chapter 1"),
        )
        val fetchUseCase = mockk<FetchAudiobookChaptersUseCase>().also { uc ->
            coEvery { uc(any<LibraryItem>()) } returns chapters
        }

        val vm = makeVm(item = audiobookItem, fetchAudiobookChaptersUseCase = fetchUseCase)
        backgroundScope.launch { vm.chaptersState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChaptersState.Ready(chapters), vm.chaptersState.value)
    }

    @Test
    fun `tocState stays Loading for non-EPUB items`() = runTest {
        val extractUseCase = mockk<ExtractEpubTocUseCase>()

        val vm = makeVm(item = audioOnlyItem, extractEpubTocUseCase = extractUseCase)
        backgroundScope.launch { vm.tocState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TocState.Loading, vm.tocState.value)
        coVerify(exactly = 0) { extractUseCase(any<LibraryItem>()) }
    }

    @Test
    fun `chaptersState stays Loading for EPUB-only items`() = runTest {
        val fetchUseCase = mockk<FetchAudiobookChaptersUseCase>()

        val vm = makeVm(item = epubItem, fetchAudiobookChaptersUseCase = fetchUseCase)
        backgroundScope.launch { vm.chaptersState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChaptersState.Loading, vm.chaptersState.value)
        coVerify(exactly = 0) { fetchUseCase(any<LibraryItem>()) }
    }

    @Test
    fun `both tocState and chaptersState transition to Ready for a combined ebook+audiobook item`() = runTest {
        val combinedItem = epubItem.copy(id = "item-combined", hasAudio = true)
        val entries = listOf(TocEntry("Chapter 1", "ch1.html"))
        val chapters = listOf(AudiobookChapter(index = 0, startSec = 0.0, endSec = 600.0, title = "Chapter 1"))
        val extractUseCase = mockk<ExtractEpubTocUseCase>().also { uc ->
            coEvery { uc(any<LibraryItem>()) } returns entries
        }
        val fetchUseCase = mockk<FetchAudiobookChaptersUseCase>().also { uc ->
            coEvery { uc(any<LibraryItem>()) } returns chapters
        }

        val vm = makeVm(
            item = combinedItem,
            extractEpubTocUseCase = extractUseCase,
            fetchAudiobookChaptersUseCase = fetchUseCase,
        )
        backgroundScope.launch { vm.tocState.collect {} }
        backgroundScope.launch { vm.chaptersState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TocState.Ready(entries), vm.tocState.value)
        assertEquals(ChaptersState.Ready(chapters), vm.chaptersState.value)
    }
}
