package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.library.LibraryItemDetailUiState.Ready
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.models.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.PendingSource
import java.io.IOException
import com.riffle.core.models.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceUrl
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.SyncSessionResult
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.CoroutineStart
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val knownItem = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Dune",
        author = "Frank Herbert",
        coverUrl = null,
        readingProgress = 0.5f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun fakeRepo(
        item: LibraryItem? = null,
        itemFlow: MutableStateFlow<LibraryItem?> = MutableStateFlow(item),
    ): LibraryObserver = object : LibraryObserver {
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
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = item
        override fun observeItem(itemId: String): Flow<LibraryItem?> = itemFlow
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.models.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun throwingRepo(): LibraryObserver = object : LibraryObserver {
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
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = throw RuntimeException("DB unavailable")
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.models.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private val noOpServerRepo = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(IOException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val noOpTokenStorage = object : TokenStorage {
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun deleteToken(sourceId: String) {}
    }

    private class FakeEpubRepository(
        var downloadResult: EpubDownloadResult = EpubDownloadResult.Success,
        private val initialDownloaded: Boolean = false,
        private val cachedIds: Set<String> = emptySet(),
        // When set, downloadEpub suspends on this gate before completing — models an in-flight
        // download so a test can observe InProgress and then release it.
        private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : EpubRepository {
        private var downloaded = initialDownloaded
        override suspend fun openEpub(item: LibraryItem): EpubOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit): EpubDownloadResult {
            gate?.await()
            if (downloadResult is EpubDownloadResult.Success) downloaded = true
            return downloadResult
        }
        override suspend fun removeDownload(sourceId: String, itemId: String) { downloaded = false }
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = downloaded
        override fun isCached(sourceId: String, itemId: String): Boolean = itemId in cachedIds
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private class FakePdfRepository : PdfRepository {
        private var downloaded = false
        override suspend fun openPdf(item: LibraryItem): PdfOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit): PdfDownloadResult {
            downloaded = true
            return PdfDownloadResult.Success
        }
        override suspend fun removeDownload(sourceId: String, itemId: String) { downloaded = false }
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = downloaded
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private val noOpSessionRepository = object : ReadingSessionRepository {
        override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
        override suspend fun markFinished(itemId: String, finished: Boolean) = Unit
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
    }

    /** Records every (itemId, finished) handed to markFinished so a test can assert the fan-out. */
    private class RecordingSessionRepository : ReadingSessionRepository {
        val markFinishedCalls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
        override suspend fun markFinished(itemId: String, finished: Boolean) { markFinishedCalls += itemId to finished }
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
    }

    private class NoopPlaylistsRepository : com.riffle.core.data.PlaylistsRepository {
        override fun observePlaylists(rootId: String) = flowOf(emptyList<com.riffle.core.catalog.CatalogPlaylist>())
        override suspend fun refresh(rootId: String) = true
        override suspend fun getPlaylist(rootId: String, playlistId: String) = null
        override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?) = throw UnsupportedOperationException()
        override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String) = false
        override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String) = false
    }

    private class FakeToReadRepository(
        initial: Set<String> = emptySet(),
        var addResult: Boolean = true,
        var removeResult: Boolean = true,
    ) : ToReadRepository {
        val state = mutableSetOf<String>().also { it += initial }
        val addCalls = mutableListOf<Pair<String, String>>()
        val removeCalls = mutableListOf<Pair<String, String>>()
        val callLog = mutableListOf<String>()

        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(state.toSet())

        override suspend fun refresh(libraryId: String): Boolean {
            callLog += "refresh"
            return true
        }

        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean {
            callLog += "isInToRead"
            return libraryItemId in state
        }

        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
            addCalls += libraryItemId to libraryId
            if (addResult) state += libraryItemId
            return addResult
        }

        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
            removeCalls += libraryItemId to libraryId
            if (removeResult) state -= libraryItemId
            return removeResult
        }
    }

    /** refresh() suspends forever, modelling a slow/unreachable server. */
    private class BlockingRefreshToReadRepository : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun refresh(libraryId: String): Boolean = kotlinx.coroutines.awaitCancellation()
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private fun makeVm(
        repo: LibraryObserver,
        itemId: String = "item-1",
        epubRepository: EpubRepository = FakeEpubRepository(),
        pdfRepository: PdfRepository = FakePdfRepository(),
        toReadRepo: ToReadRepository = FakeToReadRepository(),
        sourceRepository: SourceRepository = noOpServerRepo,
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
        downloadManager: DownloadManager = DownloadManager(kotlinx.coroutines.CoroutineScope(testDispatcher)),
        sessionRepository: ReadingSessionRepository = noOpSessionRepository,
        readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository = NoopReadaloudLinkRepository,
        readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository = NoopReadaloudAudioRepository,
        crossEpubIndexBuildTrigger: com.riffle.core.data.CrossEpubIndexBuildTrigger = RecordingBuildTrigger(),
        extractEpubTocUseCase: ExtractEpubTocUseCase = io.mockk.mockk<ExtractEpubTocUseCase>().also { uc ->
            io.mockk.coEvery { uc(any<com.riffle.core.models.LibraryItem>()) } returns emptyList<com.riffle.core.models.TocEntry>()
        },
        fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase = io.mockk.mockk<FetchAudiobookChaptersUseCase>().also { uc ->
            io.mockk.coEvery { uc(any<com.riffle.core.models.LibraryItem>()) } returns emptyList<com.riffle.core.domain.AudiobookChapter>()
        },
        catalogRegistryOverride: com.riffle.core.catalog.CatalogRegistry = detailFakeCatalogRegistry(),
    ) = LibraryItemDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
        libraryObserver = repo,
        recordItemOpened = com.riffle.app.testing.NoopRecordItemOpened(),
        updateReadingProgressUseCase = com.riffle.app.testing.NoopUpdateReadingProgress(),
        markReadAcrossDimensions = com.riffle.core.domain.usecase.MarkReadAcrossDimensions(
            libraryMutator = com.riffle.app.testing.NoopLibraryMutator,
            readingSessionRepository = sessionRepository,
            readaloudLinkRepository = readaloudLinkRepository,
            sourceRepository = sourceRepository,
        ),
        sourceRepository = sourceRepository,
        tokenStorage = noOpTokenStorage,
        epubRepository = epubRepository,
        pdfRepository = pdfRepository,
        cbzRepository = NoopCbzRepository(),
        toReadRepository = toReadRepo,
        playlistsRepository = NoopPlaylistsRepository(),
        readaloudLinkRepository = readaloudLinkRepository,
        readaloudAudioRepository = readaloudAudioRepository,
        audiobookDownloadRepository = NoopAudiobookDownloadRepository,
        readaloudOfflineDownloader = object : com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader {
            // Not streaming-eligible in these tests → null routes to the bundle download path.
            override suspend fun download(storytellerSourceId: String, storytellerBookId: String, onProgress: (Float) -> Unit): Boolean? = null
        },
        connectivityObserver = connectivityObserver,
        downloadManager = downloadManager,
        crossEpubIndexBuildTrigger = crossEpubIndexBuildTrigger,
        sidecarPrefetcher = { _, _ -> },
        extractEpubTocUseCase = extractEpubTocUseCase,
        fetchAudiobookChaptersUseCase = fetchAudiobookChaptersUseCase,
        catalogRegistry = catalogRegistryOverride,
        libraryRefresher = com.riffle.app.testing.NoopLibraryRefresher,
    )

    // These tests exercise ViewModel state and side-effects; none read Ready.capabilities.
    // Returning null keeps the fake tiny.
    private fun detailFakeCatalogRegistry(): com.riffle.core.catalog.CatalogRegistry =
        object : com.riffle.core.catalog.CatalogRegistry {
            override suspend fun forActive(): com.riffle.core.catalog.Catalog? = null
            override suspend fun forSource(source: com.riffle.core.models.Source): com.riffle.core.catalog.Catalog? = null
            override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog? = null
        }

    /** Records the links handed to the index-build trigger (the download-complete trigger, ADR 0031). */
    private class RecordingBuildTrigger : com.riffle.core.data.CrossEpubIndexBuildTrigger {
        val enqueued = mutableListOf<com.riffle.core.models.ReadaloudLink>()
        override fun enqueueBuild(link: com.riffle.core.models.ReadaloudLink) { enqueued += link }
    }

    private fun linkRepoReturning(link: com.riffle.core.models.ReadaloudLink) =
        object : com.riffle.core.domain.ReadaloudLinkRepository {
            override fun observeAll() = flowOf(listOf(link))
            override fun observeLinkedAbsItemIds() = flowOf(setOf(link.absLibraryItemId))
            override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) = link
            override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) = listOf(link)
            override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
            override suspend fun countForSource(sourceId: String) = 1
        }

    /**
     * A link repo where the opened item and [allLinks] all share one Storyteller book — models a
     * readaloud's ebook + audiobook as two coupled ABS items.
     */
    private fun linkRepoCoupling(allLinks: List<com.riffle.core.models.ReadaloudLink>) =
        object : com.riffle.core.domain.ReadaloudLinkRepository {
            override fun observeAll() = flowOf(allLinks)
            override fun observeLinkedAbsItemIds() = flowOf(allLinks.map { it.absLibraryItemId }.toSet())
            override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) =
                allLinks.firstOrNull { it.absSourceId == absSourceId && it.absLibraryItemId == absLibraryItemId }
            override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) =
                allLinks.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
            override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
            override suspend fun countForSource(sourceId: String) = allLinks.size
        }

    private fun serverRepoReturning(server: Source) = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(listOf(server))
        override suspend fun getActive(): Source? = server
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(IOException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    // --- existing uiState tests ---

    @Test
    fun `uiState is Loading before repository responds`() = runTest {
        val vm = makeVm(fakeRepo(knownItem))
        assertEquals(LibraryItemDetailUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `uiState is Ready with the item when repository returns it`() = runTest {
        val vm = makeVm(fakeRepo(knownItem))
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        // hasPlaylists is now universally true because ToReadRepository has a local fallback for
        // Sources without a server-side PlaylistsCapability (see LocalToReadStore).
        assertEquals(
            LibraryItemDetailUiState.Ready(
                knownItem,
                capabilities = DetailCapabilities(hasSeries = false, hasPlaylists = true, hasAudiobookMedia = false),
            ),
            vm.uiState.value,
        )
    }

    @Test
    fun `uiState is Error when repository returns null`() = runTest {
        val vm = makeVm(fakeRepo(item = null))
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LibraryItemDetailUiState.Error, vm.uiState.value)
    }

    @Test
    fun `uiState is Error when repository throws`() = runTest {
        val vm = makeVm(throwingRepo())
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LibraryItemDetailUiState.Error, vm.uiState.value)
    }

    // Regression for "book details shows a different (stale) progress than the reader." The screen
    // is retained on the back stack while the user reads; the reader persists new readingProgress to
    // the DB on close. A one-shot getItem snapshot would keep showing the pre-reading value, so the
    // VM observes the item row and patches the live progress into the Ready state.
    @Test
    fun `readingProgress updates reactively when the item row changes after reading`() = runTest {
        val itemFlow = MutableStateFlow<LibraryItem?>(knownItem) // starts at 0.5
        val vm = makeVm(fakeRepo(knownItem, itemFlow))
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.5f, (vm.uiState.value as Ready).item.readingProgress)

        // Reader closes and writes 0.8 to the DB; the observed row re-emits.
        itemFlow.value = knownItem.copy(readingProgress = 0.8f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.8f, (vm.uiState.value as Ready).item.readingProgress)
    }

    // --- downloadState tests ---

    @Test
    fun `downloadState is NotDownloaded when item is not in downloads store`() = runTest {
        val vm = makeVm(fakeRepo(knownItem), epubRepository = FakeEpubRepository(initialDownloaded = false))
        backgroundScope.launch { vm.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadState.NotDownloaded, vm.downloadState.value)
    }

    @Test
    fun `downloadState is Downloaded when item is already in downloads store`() = runTest {
        val vm = makeVm(fakeRepo(knownItem), epubRepository = FakeEpubRepository(initialDownloaded = true))
        backgroundScope.launch { vm.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadState.Downloaded, vm.downloadState.value)
    }

    @Test
    fun `startDownload transitions NotDownloaded to InProgress then Downloaded`() = runTest {
        val fakeEpub = FakeEpubRepository()
        val vm = makeVm(fakeRepo(knownItem), epubRepository = fakeEpub)
        backgroundScope.launch { vm.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startDownload()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadState.Downloaded, vm.downloadState.value)
    }

    @Test
    fun `removeDownload transitions Downloaded back to NotDownloaded`() = runTest {
        val fakeEpub = FakeEpubRepository(initialDownloaded = true)
        val vm = makeVm(fakeRepo(knownItem), epubRepository = fakeEpub)
        backgroundScope.launch { vm.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeDownload()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadState.NotDownloaded, vm.downloadState.value)
    }

    // The reported bug: starting a download then navigating away (which clears the ViewModel and its
    // viewModelScope) killed the download. Downloads now run on the app-scoped DownloadManager, so a
    // freshly recreated VM for the same item must still observe the in-flight download and its
    // completion.
    @Test
    fun `a download survives navigation - a recreated VM still sees it in progress then completing`() = runTest {
        val manager = DownloadManager(kotlinx.coroutines.CoroutineScope(testDispatcher))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val epub = FakeEpubRepository(gate = gate)

        val vm1 = makeVm(fakeRepo(knownItem), epubRepository = epub, downloadManager = manager)
        backgroundScope.launch { vm1.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        vm1.startDownload()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("download should be in progress", vm1.downloadState.value is DownloadState.InProgress)

        // User navigates away; a new VM is created for the same item, sharing the singleton manager.
        val vm2 = makeVm(fakeRepo(knownItem), epubRepository = epub, downloadManager = manager)
        backgroundScope.launch { vm2.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "recreated VM should observe the still-running download",
            vm2.downloadState.value is DownloadState.InProgress,
        )

        // The download finishes while we're on the recreated screen.
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DownloadState.Downloaded, vm2.downloadState.value)
    }

    @Test
    fun `startDownload reverts to NotDownloaded when download fails`() = runTest {
        val fakeEpub = FakeEpubRepository(downloadResult = EpubDownloadResult.NetworkError(RuntimeException("network")))
        val vm = makeVm(fakeRepo(knownItem), epubRepository = fakeEpub)
        backgroundScope.launch { vm.downloadState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startDownload()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadState.NotDownloaded, vm.downloadState.value)
    }

    @Test
    fun `init shows local To Read state immediately then refreshes from the server`() = runTest {
        // Local cache says the book is in To Read; the server refresh will confirm it.
        val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        // The first isInToRead read happens before the refresh — Ready is not gated on the network.
        val firstIsIn = toRead.callLog.indexOf("isInToRead")
        val refreshIdx = toRead.callLog.indexOf("refresh")
        assertTrue("expected isInToRead before refresh, got ${toRead.callLog}", firstIsIn in 0 until refreshIdx)
        assertTrue((vm.uiState.value as Ready).isInToRead)
    }

    @Test
    fun `uiState reaches Ready without waiting for the To Read server refresh`() = runTest {
        // refresh() never completes — simulates a slow/unreachable ABS server. The detail screen
        // must still render from local data instead of sitting in Loading for the network timeout.
        val toRead = BlockingRefreshToReadRepository()
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("expected Ready while refresh is still in flight", vm.uiState.value is Ready)
    }

    // --- toggleToRead tests ---

    @Test
    fun `toggleToRead optimistically flips state and persists on success`() = runTest {
        val toRead = FakeToReadRepository()
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse((vm.uiState.value as Ready).isInToRead)

        vm.toggleToRead()
        assertTrue((vm.uiState.value as Ready).isInToRead)

        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.uiState.value as Ready).isInToRead)
        assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.addCalls)
    }

    @Test
    fun `toggleToRead reverts state and emits snackbar on failure`() = runTest(testDispatcher) {
        val toRead = FakeToReadRepository(addResult = false)
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        val snackbarMessages = mutableListOf<String>()
        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.snackbarEvents.collect { snackbarMessages += it }
        }
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleToRead()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse((vm.uiState.value as Ready).isInToRead)
        assertEquals(1, snackbarMessages.size)
        assertTrue(snackbarMessages.single().contains("To Read", ignoreCase = true))
        collectorJob.cancel()
    }

    @Test
    fun `toggleToRead emits success snackbar on add and remove`() = runTest(testDispatcher) {
        val toRead = FakeToReadRepository()
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        val snackbarMessages = mutableListOf<String>()
        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.snackbarEvents.collect { snackbarMessages += it }
        }
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleToRead()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("Added to To Read"), snackbarMessages)

        vm.toggleToRead()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("Added to To Read", "Removed from To Read"), snackbarMessages)

        collectorJob.cancel()
    }

    @Test
    fun `toggleToRead removes book when already in To Read`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.uiState.value as Ready).isInToRead)

        vm.toggleToRead()
        assertFalse((vm.uiState.value as Ready).isInToRead)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse((vm.uiState.value as Ready).isInToRead)
        assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.removeCalls)
    }

    // --- markAsRead / markAsUnread coupling to To Read (ADR 0018) ---

    @Test
    fun `markAsRead also removes the book from To Read`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.markAsRead()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(knownItem.id to knownItem.libraryId), toRead.removeCalls)
        assertFalse((vm.uiState.value as Ready).isInToRead)
    }

    // Bug 1: marking the ebook read must also mark its readaloud-coupled audiobook (a separate ABS
    // item) finished, so the two don't disagree.
    @Test
    fun `markAsRead marks every readaloud-coupled item finished`() = runTest {
        val ebookLink = com.riffle.core.models.ReadaloudLink(
            storytellerSourceId = "st-1", storytellerBookId = "book-1",
            absSourceId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val audiobookLink = ebookLink.copy(absLibraryItemId = "audio-2")
        val session = RecordingSessionRepository()
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            sessionRepository = session,
            readaloudLinkRepository = linkRepoCoupling(listOf(ebookLink, audiobookLink)),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.markAsRead()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            setOf("item-1" to true, "audio-2" to true),
            session.markFinishedCalls.toSet(),
        )
    }

    // Bug 2: marking unread must reset BOTH coupled items to 0 so a surviving audiobook progress
    // can't reappear as ghost progress.
    @Test
    fun `markAsUnread marks every readaloud-coupled item not finished`() = runTest {
        val ebookLink = com.riffle.core.models.ReadaloudLink(
            storytellerSourceId = "st-1", storytellerBookId = "book-1",
            absSourceId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val audiobookLink = ebookLink.copy(absLibraryItemId = "audio-2")
        val session = RecordingSessionRepository()
        val vm = makeVm(
            repo = fakeRepo(knownItem.copy(readingProgress = 1.0f)),
            sourceRepository = serverRepoReturning(activeServer()),
            sessionRepository = session,
            readaloudLinkRepository = linkRepoCoupling(listOf(ebookLink, audiobookLink)),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.markAsUnread()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            setOf("item-1" to false, "audio-2" to false),
            session.markFinishedCalls.toSet(),
        )
    }

    @Test
    fun `markAsUnread does not touch To Read`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf(knownItem.id))
        val vm = makeVm(repo = fakeRepo(knownItem.copy(readingProgress = 1.0f)), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.markAsUnread()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(toRead.removeCalls.isEmpty())
        assertTrue((vm.uiState.value as Ready).isInToRead)
    }

    @Test
    fun `toggleToRead on a Read book does not clear the Read flag`() = runTest {
        val readItem = knownItem.copy(readingProgress = 1.0f)
        val toRead = FakeToReadRepository()
        val vm = makeVm(repo = fakeRepo(readItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleToRead()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.0f, (vm.uiState.value as Ready).item.readingProgress, 0.0001f)
        assertTrue((vm.uiState.value as Ready).isInToRead)
    }

    // --- Ready.isCachedOrDownloaded / Ready.isOffline ---

    // ADR 0031: downloading the readaloud bundle is the deterministic moment the cross-EPUB index's
    // only un-fetchable prerequisite (the bundle) arrives — so a successful download enqueues the build.
    @Test
    fun `a successful readaloud download enqueues a cross-EPUB index build for the link`() = runTest {
        val link = com.riffle.core.models.ReadaloudLink(
            storytellerSourceId = "st-1", storytellerBookId = "book-1",
            absSourceId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val trigger = RecordingBuildTrigger()
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            readaloudLinkRepository = linkRepoReturning(link),
            crossEpubIndexBuildTrigger = trigger,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle() // load resolves readaloudLink

        vm.onDownloadReadaloud()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(link), trigger.enqueued)
    }

    // A failed download must NOT enqueue a build (the bundle isn't present, so the build would defer).
    @Test
    fun `a failed readaloud download does not enqueue an index build`() = runTest {
        val link = com.riffle.core.models.ReadaloudLink(
            storytellerSourceId = "st-1", storytellerBookId = "book-1",
            absSourceId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val trigger = RecordingBuildTrigger()
        val failingAudio = object : com.riffle.core.domain.ReadaloudAudioRepository by NoopReadaloudAudioRepository {
            override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit) =
                com.riffle.core.domain.AudioDownloadResult.NoBundle
        }
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            readaloudLinkRepository = linkRepoReturning(link),
            readaloudAudioRepository = failingAudio,
            crossEpubIndexBuildTrigger = trigger,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onDownloadReadaloud()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(trigger.enqueued.isEmpty())
    }

    private fun activeServer() = Source(
        id = "abs-1",
        url = com.riffle.core.models.SourceUrl.parse("http://media-server:13378")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.models.ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun `Ready state exposes isCachedOrDownloaded true when epub is cached`() = runTest {
        val fakeEpub = FakeEpubRepository(cachedIds = setOf(knownItem.id))
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            epubRepository = fakeEpub,
            sourceRepository = serverRepoReturning(activeServer()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as Ready
        assertTrue(state.isCachedOrDownloaded)
    }

    @Test
    fun `Ready state exposes isCachedOrDownloaded true when epub is downloaded`() = runTest {
        val fakeEpub = FakeEpubRepository(initialDownloaded = true)
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            epubRepository = fakeEpub,
            sourceRepository = serverRepoReturning(activeServer()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as Ready
        assertTrue(state.isCachedOrDownloaded)
    }

    @Test
    fun `Ready state exposes isCachedOrDownloaded false when epub is neither cached nor downloaded`() = runTest {
        val fakeEpub = FakeEpubRepository()
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            epubRepository = fakeEpub,
            sourceRepository = serverRepoReturning(activeServer()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as Ready
        assertFalse(state.isCachedOrDownloaded)
    }

    @Test
    fun `Ready state exposes isOffline false when device is online`() = runTest {
        val connectivity = FakeConnectivityObserver(online = true)
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            connectivityObserver = connectivity,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse((vm.uiState.value as Ready).isOffline)
    }

    @Test
    fun `Ready state exposes isOffline true when device is offline`() = runTest {
        val connectivity = FakeConnectivityObserver(online = false)
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            connectivityObserver = connectivity,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue((vm.uiState.value as Ready).isOffline)
    }

    @Test
    fun `isOffline in Ready state updates reactively when connectivity changes`() = runTest {
        val connectivity = FakeConnectivityObserver(online = true)
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            sourceRepository = serverRepoReturning(activeServer()),
            connectivityObserver = connectivity,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse((vm.uiState.value as Ready).isOffline)

        connectivity.state.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue((vm.uiState.value as Ready).isOffline)
    }

    // --- isCachedOrDownloaded refresh after download/remove (#35) ---

    @Test
    fun `Ready isCachedOrDownloaded refreshes after startDownload succeeds`() = runTest {
        val fakeEpub = FakeEpubRepository() // not downloaded initially
        val vm = makeVm(repo = fakeRepo(knownItem), epubRepository = fakeEpub)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val before = vm.uiState.value as Ready
        assertFalse(before.isCachedOrDownloaded)

        vm.startDownload()
        testDispatcher.scheduler.advanceUntilIdle()

        val after = vm.uiState.value as Ready
        assertTrue(after.isCachedOrDownloaded)
    }

    @Test
    fun `Ready isCachedOrDownloaded refreshes after removeDownload`() = runTest {
        val fakeEpub = FakeEpubRepository(initialDownloaded = true)
        val vm = makeVm(repo = fakeRepo(knownItem), epubRepository = fakeEpub)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val before = vm.uiState.value as Ready
        assertTrue(before.isCachedOrDownloaded)

        vm.removeDownload()
        testDispatcher.scheduler.advanceUntilIdle()

        val after = vm.uiState.value as Ready
        assertFalse(after.isCachedOrDownloaded)
    }

    // Regression for "LocalFiles item shows a Download button." A LocalFiles item lives on the
    // device already — its Catalog omits DownloadsCapability, so the detail screen must not surface
    // the download affordances (ebook, audiobook, readaloud bundle).
    @Test
    fun `capabilities hasDownloads is false when the item's Catalog lacks DownloadsCapability`() = runTest {
        val localItem = knownItem.copy(sourceId = "local-source")
        val vm = makeVm(
            fakeRepo(localItem),
            catalogRegistryOverride = fakeCatalogRegistry(NonDownloadsCatalog),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val ready = vm.uiState.value as Ready
        assertFalse(
            "Source without DownloadsCapability must not flag hasDownloads — the detail screen would render dead download buttons.",
            ready.capabilities.hasDownloads,
        )
        assertFalse(
            "Source without ReadaloudCapability must not flag hasReadaloud.",
            ready.capabilities.hasReadaloud,
        )
    }

    // Companion: an ABS item's Catalog declares DownloadsCapability + ReadaloudCapability so the
    // detail screen surfaces the download affordances.
    @Test
    fun `capabilities hasDownloads is true when the item's Catalog declares DownloadsCapability`() = runTest {
        val absItem = knownItem.copy(sourceId = "abs-source")
        val vm = makeVm(
            fakeRepo(absItem),
            catalogRegistryOverride = fakeCatalogRegistry(DownloadsAndReadaloudCatalog),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val ready = vm.uiState.value as Ready
        assertTrue(ready.capabilities.hasDownloads)
        assertTrue(ready.capabilities.hasReadaloud)
    }

    private object NonDownloadsCatalog : com.riffle.core.catalog.Catalog by NoopCatalog

    private object DownloadsAndReadaloudCatalog :
        com.riffle.core.catalog.Catalog by NoopCatalog,
        com.riffle.core.catalog.DownloadsCapability,
        com.riffle.core.catalog.ReadaloudCapability

    private object NoopCatalog : com.riffle.core.catalog.Catalog {
        override val sourceType: com.riffle.core.models.SourceType = com.riffle.core.models.SourceType.ABS
        override suspend fun listRoots(): List<com.riffle.core.catalog.CatalogRoot> = emptyList()
        override suspend fun browse(
            rootId: String,
            sort: com.riffle.core.catalog.SortKey,
            page: Int,
            pageSize: Int,
            facet: com.riffle.core.catalog.FacetSelection?,
        ): List<com.riffle.core.catalog.CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<com.riffle.core.catalog.CatalogItem>()
        override suspend fun getItem(itemId: String): com.riffle.core.catalog.CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: com.riffle.core.catalog.BookFormat) =
            throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: com.riffle.core.catalog.BookFormat, handleHint: String?) =
            throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = com.riffle.core.catalog.CatalogHealth(isReachable = true)
    }

    private fun fakeCatalogRegistry(catalog: com.riffle.core.catalog.Catalog): com.riffle.core.catalog.CatalogRegistry =
        object : com.riffle.core.catalog.CatalogRegistry {
            override suspend fun forActive(): com.riffle.core.catalog.Catalog = catalog
            override suspend fun forSource(source: com.riffle.core.models.Source): com.riffle.core.catalog.Catalog = catalog
            override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog = catalog
        }
}
