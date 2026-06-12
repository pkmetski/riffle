package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.library.LibraryItemDetailUiState.Ready
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.PendingServer
import java.io.IOException
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
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SyncSessionResult
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
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = item
        override fun observeItem(itemId: String): Flow<LibraryItem?> = itemFlow
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

    private fun throwingRepo(): LibraryRepository = object : LibraryRepository {
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
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = throw RuntimeException("DB unavailable")
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow(null)
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

    private val noOpServerRepo = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
            AuthenticateResult.WrongCredentials()
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
            CommitServerResult.Failure(IOException())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private val noOpTokenStorage = object : TokenStorage {
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun deleteToken(serverId: String) {}
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
        override suspend fun removeDownload(serverId: String, itemId: String) { downloaded = false }
        override fun isDownloaded(serverId: String, itemId: String): Boolean = downloaded
        override fun isCached(serverId: String, itemId: String): Boolean = itemId in cachedIds
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
        override suspend fun removeDownload(serverId: String, itemId: String) { downloaded = false }
        override fun isDownloaded(serverId: String, itemId: String): Boolean = downloaded
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private val noOpSessionRepository = object : ReadingSessionRepository {
        override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult = ProgressSyncCycleResult.InSync
        override suspend fun setProgress(itemId: String, progress: Float) = Unit
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
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
        repo: LibraryRepository,
        itemId: String = "item-1",
        epubRepository: EpubRepository = FakeEpubRepository(),
        pdfRepository: PdfRepository = FakePdfRepository(),
        toReadRepo: ToReadRepository = FakeToReadRepository(),
        serverRepository: ServerRepository = noOpServerRepo,
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
        downloadManager: DownloadManager = DownloadManager(kotlinx.coroutines.CoroutineScope(testDispatcher)),
        readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository = NoopReadaloudLinkRepository,
        readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository = NoopReadaloudAudioRepository,
        crossEpubIndexBuildTrigger: com.riffle.core.data.CrossEpubIndexBuildTrigger = RecordingBuildTrigger(),
    ) = LibraryItemDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
        repository = repo,
        serverRepository = serverRepository,
        tokenStorage = noOpTokenStorage,
        epubRepository = epubRepository,
        pdfRepository = pdfRepository,
        sessionRepository = noOpSessionRepository,
        toReadRepository = toReadRepo,
        readaloudLinkRepository = readaloudLinkRepository,
        readaloudAudioRepository = readaloudAudioRepository,
        audiobookDownloadRepository = NoopAudiobookDownloadRepository,
        connectivityObserver = connectivityObserver,
        downloadManager = downloadManager,
        crossEpubIndexBuildTrigger = crossEpubIndexBuildTrigger,
    )

    /** Records the links handed to the index-build trigger (the download-complete trigger, ADR 0031). */
    private class RecordingBuildTrigger : com.riffle.core.data.CrossEpubIndexBuildTrigger {
        val enqueued = mutableListOf<com.riffle.core.domain.ReadaloudLink>()
        override fun enqueueBuild(link: com.riffle.core.domain.ReadaloudLink) { enqueued += link }
    }

    private fun linkRepoReturning(link: com.riffle.core.domain.ReadaloudLink) =
        object : com.riffle.core.domain.ReadaloudLinkRepository {
            override fun observeAll() = flowOf(listOf(link))
            override fun observeLinkedAbsItemIds() = flowOf(setOf(link.absLibraryItemId))
            override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) = link
            override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) = listOf(link)
            override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
            override suspend fun countForServer(serverId: String) = 1
        }

    private fun serverRepoReturning(server: Server) = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(listOf(server))
        override suspend fun getActive(): Server? = server
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
            AuthenticateResult.WrongCredentials()
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
            CommitServerResult.Failure(IOException())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
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

        assertEquals(LibraryItemDetailUiState.Ready(knownItem), vm.uiState.value)
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
        val link = com.riffle.core.domain.ReadaloudLink(
            storytellerServerId = "st-1", storytellerBookId = "book-1",
            absServerId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val trigger = RecordingBuildTrigger()
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            serverRepository = serverRepoReturning(activeServer()),
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
        val link = com.riffle.core.domain.ReadaloudLink(
            storytellerServerId = "st-1", storytellerBookId = "book-1",
            absServerId = "abs-1", absLibraryItemId = "item-1", userConfirmed = true,
        )
        val trigger = RecordingBuildTrigger()
        val failingAudio = object : com.riffle.core.domain.ReadaloudAudioRepository by NoopReadaloudAudioRepository {
            override suspend fun downloadAudio(serverId: String, bookId: String, onProgress: (Long, Long) -> Unit) =
                com.riffle.core.domain.AudioDownloadResult.NoBundle
        }
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            serverRepository = serverRepoReturning(activeServer()),
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

    private fun activeServer() = Server(
        id = "abs-1",
        url = com.riffle.core.domain.ServerUrl.parse("http://media-server:13378")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.domain.ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun `Ready state exposes isCachedOrDownloaded true when epub is cached`() = runTest {
        val fakeEpub = FakeEpubRepository(cachedIds = setOf(knownItem.id))
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            epubRepository = fakeEpub,
            serverRepository = serverRepoReturning(activeServer()),
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
            serverRepository = serverRepoReturning(activeServer()),
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
            serverRepository = serverRepoReturning(activeServer()),
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
            serverRepository = serverRepoReturning(activeServer()),
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
            serverRepository = serverRepoReturning(activeServer()),
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
            serverRepository = serverRepoReturning(activeServer()),
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
}
