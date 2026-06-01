package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.library.LibraryItemDetailUiState.Ready
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.Collection
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

    private fun fakeRepo(item: LibraryItem? = null): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
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
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private fun throwingRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
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
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
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
    ) : EpubRepository {
        private var downloaded = initialDownloaded
        override suspend fun openEpub(item: LibraryItem): EpubOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadEpub(item: LibraryItem): EpubDownloadResult {
            if (downloadResult is EpubDownloadResult.Success) downloaded = true
            return downloadResult
        }
        override suspend fun removeDownload(itemId: String) { downloaded = false }
        override fun isDownloaded(itemId: String): Boolean = downloaded
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakePdfRepository : PdfRepository {
        private var downloaded = false
        override suspend fun openPdf(item: LibraryItem): PdfOpenResult = throw UnsupportedOperationException()
        override suspend fun downloadPdf(item: LibraryItem): PdfDownloadResult {
            downloaded = true
            return PdfDownloadResult.Success
        }
        override suspend fun removeDownload(itemId: String) { downloaded = false }
        override fun isDownloaded(itemId: String): Boolean = downloaded
        override fun isCached(itemId: String): Boolean = false
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

    private fun makeVm(
        repo: LibraryRepository,
        itemId: String = "item-1",
        epubRepository: EpubRepository = FakeEpubRepository(),
        pdfRepository: PdfRepository = FakePdfRepository(),
        toReadRepo: ToReadRepository = FakeToReadRepository(),
        serverRepository: ServerRepository = noOpServerRepo,
    ) = LibraryItemDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
        repository = repo,
        serverRepository = serverRepository,
        tokenStorage = noOpTokenStorage,
        epubRepository = epubRepository,
        pdfRepository = pdfRepository,
        sessionRepository = noOpSessionRepository,
        toReadRepository = toReadRepo,
        readaloudLinkRepository = NoopReadaloudLinkRepository,
    )

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
    fun `init refreshes To Read before reading isInToRead`() = runTest {
        val toRead = FakeToReadRepository()
        val vm = makeVm(repo = fakeRepo(knownItem), toReadRepo = toRead)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val refreshIdx = toRead.callLog.indexOf("refresh")
        val isInIdx = toRead.callLog.indexOf("isInToRead")
        assertTrue("expected refresh before isInToRead, got ${toRead.callLog}", refreshIdx in 0 until isInIdx)
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

    // --- Readaloud (Storyteller) detail mode ---

    private fun storytellerServer() = Server(
        id = "st-1",
        url = com.riffle.core.domain.ServerUrl.parse("http://media-server:8001")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.domain.ServerType.STORYTELLER,
    )

    private fun absServer() = Server(
        id = "abs-1",
        url = com.riffle.core.domain.ServerUrl.parse("http://media-server:13378")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "plamen",
        serverType = com.riffle.core.domain.ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun `isReadaloud true when active server is Storyteller`() = runTest {
        val vm = makeVm(repo = fakeRepo(knownItem), serverRepository = serverRepoReturning(storytellerServer()))
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.uiState.value as Ready).isReadaloud)
    }

    @Test
    fun `isReadaloud false when active server is Audiobookshelf`() = runTest {
        val vm = makeVm(repo = fakeRepo(knownItem), serverRepository = serverRepoReturning(absServer()))
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse((vm.uiState.value as Ready).isReadaloud)
    }

    @Test
    fun `Readaloud detail does not invoke toReadRepository - no ABS-side playlists`() = runTest {
        val toRead = FakeToReadRepository()
        val vm = makeVm(
            repo = fakeRepo(knownItem),
            toReadRepo = toRead,
            serverRepository = serverRepoReturning(storytellerServer()),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        // refresh and isInToRead both touch the ABS playlists API and would 404 against
        // Storyteller — confirm both are skipped for Readaloud detail.
        assertTrue("toReadRepository should not be called for Readaloud items, was: ${toRead.callLog}", toRead.callLog.isEmpty())
        assertFalse((vm.uiState.value as Ready).isInToRead)
    }
}
