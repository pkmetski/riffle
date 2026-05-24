package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.Collection
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
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = item
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
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = throw RuntimeException("DB unavailable")
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
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
            AddServerResult.WrongCredentials()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
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

    private fun makeVm(
        repo: LibraryRepository,
        itemId: String = "item-1",
        epubRepository: EpubRepository = FakeEpubRepository(),
        pdfRepository: PdfRepository = FakePdfRepository(),
    ) = LibraryItemDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
        repository = repo,
        serverRepository = noOpServerRepo,
        tokenStorage = noOpTokenStorage,
        epubRepository = epubRepository,
        pdfRepository = pdfRepository,
    )

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
}
