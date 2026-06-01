package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
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
import com.riffle.core.data.ToReadRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private val seriesFlow = MutableStateFlow<List<Series>>(emptyList())
    private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
    private val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val inProgressFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val finishedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val recentlyAddedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allBooksFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val collectionItemsByCollectionId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val seriesItemsBySeriesId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())

    private fun fakeRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = itemsFlow
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = inProgressFlow
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = finishedFlow
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = recentlyAddedFlow
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooksFlow
        override fun observeSeries(libraryId: String): Flow<List<Series>> = seriesFlow
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collectionsFlow
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
            seriesItemsBySeriesId.getOrPut(seriesId) { MutableStateFlow(emptyList()) }
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
            collectionItemsByCollectionId.getOrPut(collectionId) { MutableStateFlow(emptyList()) }
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
    }

    private fun fakeServerRepo(): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun deleteToken(serverId: String) {}
    }

    private fun fakeEpubRepo(): EpubRepository = object : EpubRepository {
        override suspend fun openEpub(item: LibraryItem): EpubOpenResult = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem): EpubDownloadResult = EpubDownloadResult.Success
        override suspend fun removeDownload(itemId: String) {}
        override fun isDownloaded(itemId: String): Boolean = false
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private fun fakePdfRepo(): PdfRepository = object : PdfRepository {
        override suspend fun openPdf(item: LibraryItem): PdfOpenResult = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem): PdfDownloadResult = PdfDownloadResult.Success
        override suspend fun removeDownload(itemId: String) {}
        override fun isDownloaded(itemId: String): Boolean = false
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
        val state = MutableStateFlow(online)
        override val isOnline: StateFlow<Boolean> = state
    }

    private class FakeToReadRepository(initial: Set<String> = emptySet()) : ToReadRepository {
        val ids = MutableStateFlow(initial)
        var refreshCount = 0
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = ids
        override suspend fun refresh(libraryId: String): Boolean {
            refreshCount++
            return true
        }
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean =
            libraryItemId in ids.value
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean {
            ids.value = ids.value + libraryItemId
            return true
        }
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean {
            ids.value = ids.value - libraryItemId
            return true
        }
    }

    private fun makeViewModel(
        connectivityObserver: ConnectivityObserver = FakeConnectivityObserver(),
        epubRepository: EpubRepository = fakeEpubRepo(),
        pdfRepository: PdfRepository = fakePdfRepo(),
        libraryRepository: LibraryRepository = fakeRepo(),
        serverRepository: ServerRepository = fakeServerRepo(),
        tokenStorage: TokenStorage = fakeTokenStorage(),
        toReadRepository: ToReadRepository = FakeToReadRepository(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("libraryId" to "lib-1")),
    ) = LibraryItemsViewModel(
        savedStateHandle,
        libraryRepository,
        serverRepository,
        tokenStorage,
        epubRepository,
        pdfRepository,
        connectivityObserver,
        toReadRepository,
    )

    private fun series(name: String) = Series("id-$name", "lib-1", name, null, 1)
    private fun collection(name: String) = Collection("id-$name", "lib-1", name, 1)
    private fun item(title: String, author: String, coverUrl: String? = null) = LibraryItem(
        "id-$title", "lib-1", title, author, coverUrl, 0f, false, false, EbookFormat.Epub,
    )

    // --- empty query passthrough ---

    @Test
    fun `empty query returns all series`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Mistborn"), series("Stormlight")), vm.filteredSeries.value)
    }

    @Test
    fun `empty query returns all collections`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy"), collection("Sci-Fi")), vm.filteredCollections.value)
    }

    @Test
    fun `empty query returns all ungrouped items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov")),
            vm.filteredUngroupedItems.value,
        )
    }

    // --- series filtering ---

    @Test
    fun `query filters series by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight Archive"))
        vm.onSearchQueryChange("storm")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Stormlight Archive")), vm.filteredSeries.value)
    }

    @Test
    fun `series filtering is case-insensitive`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("The Wheel of Time"))
        vm.onSearchQueryChange("WHEEL")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("The Wheel of Time")), vm.filteredSeries.value)
    }

    // --- collection filtering ---

    @Test
    fun `query filters collections by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        vm.onSearchQueryChange("sci")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Sci-Fi")), vm.filteredCollections.value)
    }

    // --- item filtering ---

    @Test
    fun `query filters items by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        vm.onSearchQueryChange("dun")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.filteredUngroupedItems.value)
    }

    @Test
    fun `query filters items by author`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        allItemsFlow.value = listOf(item("Mistborn", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("sand")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Mistborn", "Brandon Sanderson")), vm.filteredUngroupedItems.value)
    }

    @Test
    fun `query finds books that belong to series by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        // allItemsFlow contains items in series; itemsFlow (ungrouped) does not
        allItemsFlow.value = listOf(item("The Final Empire", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("final empire")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("The Final Empire", "Brandon Sanderson")), vm.filteredUngroupedItems.value)
    }

    // --- no match ---

    @Test
    fun `query with no match empties all filtered lists`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch {
            vm.filteredSeries.collect {}
            vm.filteredCollections.collect {}
            vm.filteredUngroupedItems.collect {}
        }
        seriesFlow.value = listOf(series("Mistborn"))
        collectionsFlow.value = listOf(collection("Fantasy"))
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("zzznomatch")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Series>(), vm.filteredSeries.value)
        assertEquals(emptyList<Collection>(), vm.filteredCollections.value)
        assertEquals(emptyList<LibraryItem>(), vm.filteredUngroupedItems.value)
    }

    // --- query state ---

    @Test
    fun `onSearchQueryChange updates searchQuery`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.searchQuery.collect {} }
        vm.onSearchQueryChange("hello")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello", vm.searchQuery.value)
    }

    // --- C1: filteredInProgress flow ---

    @Test
    fun `filteredInProgress emits items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredInProgress.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        inProgressFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.filteredInProgress.value)
    }

    // --- C2: filteredFinished flow ---

    @Test
    fun `filteredFinished emits items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredFinished.collect {} }
        val expected = listOf(item("1984", "George Orwell"))
        finishedFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.filteredFinished.value)
    }

    // --- C3: filteredAllBooks flow ---

    @Test
    fun `filteredAllBooks emits all items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredAllBooks.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("1984", "George Orwell"), item("Foundation", "Isaac Asimov"))
        allBooksFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.filteredAllBooks.value)
    }

    // --- C4: series and collections unchanged ---

    @Test
    fun `series flow still emits from repository`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.series.collect {} }
        seriesFlow.value = listOf(series("Mistborn"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Mistborn")), vm.series.value)
    }

    @Test
    fun `collections flow still emits from repository`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.collections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy")), vm.collections.value)
    }

    // --- collectionCoverUrls derivation ---

    @Test
    fun `collectionCoverUrls is empty when no collections`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.collectionCoverUrls.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyMap<String, List<String>>(), vm.collectionCoverUrls.value)
    }

    @Test
    fun `collectionCoverUrls maps each collection id to up to 4 member cover URLs`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.collectionCoverUrls.collect {} }
        val col = collection("Fantasy")
        collectionsFlow.value = listOf(col)
        collectionItemsByCollectionId.getOrPut(col.id) { MutableStateFlow(emptyList()) }.value = listOf(
            item("A", "X", coverUrl = "https://abs/cover/A"),
            item("B", "X", coverUrl = "https://abs/cover/B"),
            item("C", "X", coverUrl = "https://abs/cover/C"),
            item("D", "X", coverUrl = "https://abs/cover/D"),
            item("E", "X", coverUrl = "https://abs/cover/E"), // beyond the 4-cap
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            mapOf(col.id to listOf("https://abs/cover/A", "https://abs/cover/B", "https://abs/cover/C", "https://abs/cover/D")),
            vm.collectionCoverUrls.value,
        )
    }

    @Test
    fun `collectionCoverUrls skips items with null or blank cover URLs`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.collectionCoverUrls.collect {} }
        val col = collection("Mixed")
        collectionsFlow.value = listOf(col)
        collectionItemsByCollectionId.getOrPut(col.id) { MutableStateFlow(emptyList()) }.value = listOf(
            item("A", "X", coverUrl = null),
            item("B", "X", coverUrl = ""),
            item("C", "X", coverUrl = "   "),
            item("D", "X", coverUrl = "https://abs/cover/D"),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(mapOf(col.id to listOf("https://abs/cover/D")), vm.collectionCoverUrls.value)
    }

    @Test
    fun `collectionCoverUrls re-emits when a collection's items change`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.collectionCoverUrls.collect {} }
        val col = collection("Reactive")
        collectionsFlow.value = listOf(col)
        val itemsFlow = collectionItemsByCollectionId.getOrPut(col.id) { MutableStateFlow(emptyList()) }
        itemsFlow.value = listOf(item("A", "X", coverUrl = "u1"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(mapOf(col.id to listOf("u1")), vm.collectionCoverUrls.value)

        itemsFlow.value = listOf(item("A", "X", coverUrl = "u1"), item("B", "X", coverUrl = "u2"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(mapOf(col.id to listOf("u1", "u2")), vm.collectionCoverUrls.value)
    }

    // --- offline filtering ---

    private fun fakeEpubRepoWithDownloads(downloadedIds: Set<String>): EpubRepository = object : EpubRepository {
        override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem) = EpubDownloadResult.Success
        override suspend fun removeDownload(itemId: String) {}
        override fun isDownloaded(itemId: String): Boolean = itemId in downloadedIds
        override fun isCached(itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    @Test
    fun `when offline ungrouped items are filtered to only downloaded`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
        )
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.filteredUngroupedItems.value)
        assertEquals(true, vm.isOffline.value)
    }

    @Test
    fun `when online no offline filter is applied to ungrouped items`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = true),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
        )
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.filteredUngroupedItems.value.size)
        assertEquals(false, vm.isOffline.value)
    }

    @Test
    fun `isOffline becomes false when connectivity returns to online`() = runTest {
        val connectivity = FakeConnectivityObserver(online = false)
        val vm = makeViewModel(connectivityObserver = connectivity)
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.isOffline.value)

        connectivity.state.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isOffline.value)
    }

    @Test
    fun `when offline collections with no available offline items are hidden`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
        )
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        collectionItemsByCollectionId.getOrPut("id-Fantasy") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Mistborn", "Brandon Sanderson"))
        collectionItemsByCollectionId.getOrPut("id-Sci-Fi") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Dune", "Frank Herbert"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Collection>(), vm.filteredCollections.value)
    }

    @Test
    fun `when offline collections with at least one available offline item are shown`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Mistborn")),
        )
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        collectionItemsByCollectionId.getOrPut("id-Fantasy") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Mistborn", "Brandon Sanderson"))
        collectionItemsByCollectionId.getOrPut("id-Sci-Fi") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Dune", "Frank Herbert"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy")), vm.filteredCollections.value)
    }

    @Test
    fun `when offline series with no available offline items are hidden`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
        )
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        seriesItemsBySeriesId.getOrPut("id-Mistborn") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Final Empire", "Brandon Sanderson"))
        seriesItemsBySeriesId.getOrPut("id-Stormlight") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Way of Kings", "Brandon Sanderson"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Series>(), vm.filteredSeries.value)
    }

    @Test
    fun `when offline series with at least one available offline item are shown`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-The Way of Kings")),
        )
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        seriesItemsBySeriesId.getOrPut("id-Mistborn") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Final Empire", "Brandon Sanderson"))
        seriesItemsBySeriesId.getOrPut("id-Stormlight") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Way of Kings", "Brandon Sanderson"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Stormlight")), vm.filteredSeries.value)
    }

    // --- isLoading sequencing (flickering regression guard) ---

    @Test
    fun `isLoading becomes false as soon as cached data arrives from Room`() = runTest {
        allBooksFlow.value = listOf(item("Dune", "Frank Herbert"))
        val vm = makeViewModel()
        backgroundScope.launch { vm.isLoading.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isLoading.value)
    }

    @Test
    fun `isLoading remains true partway through cache window when no data`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.isLoading.collect {} }
        // Advance halfway through the 500ms cached-data window — nothing emitted yet
        testDispatcher.scheduler.advanceTimeBy(250)
        assertEquals(true, vm.isLoading.value)
    }

    @Test
    fun `isLoading becomes false after refresh completes when library has no cached data`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.isLoading.collect {} }
        // Advance past the 500ms window; fake refresh returns immediately after
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isLoading.value)
    }

    @Test
    fun `authToken is set before isLoading becomes false`() = runTest {
        val vm = makeViewModel(
            serverRepository = object : ServerRepository {
                override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
                override suspend fun getActive() = Server("srv-1", ServerUrl.parse("http://localhost")!!, true, false, "")
                override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
                    throw UnsupportedOperationException()
                override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
                    throw UnsupportedOperationException()
                override suspend fun setActive(serverId: String) {}
                override suspend fun remove(serverId: String) {}
                override suspend fun getServerVersion(serverId: String): String? = null
            },
            tokenStorage = object : TokenStorage {
                override suspend fun saveToken(serverId: String, token: String) {}
                override suspend fun getToken(serverId: String) = "tok-abc"
                override suspend fun deleteToken(serverId: String) {}
            },
        )
        backgroundScope.launch { vm.isLoading.collect {} }
        allBooksFlow.value = listOf(item("Dune", "Frank Herbert"))
        testDispatcher.scheduler.advanceUntilIdle()
        // Both must be true simultaneously: loading done AND token present
        assertEquals(false, vm.isLoading.value)
        assertEquals("tok-abc", vm.authToken)
    }

    // --- filteredRecentlyAdded ---

    @Test
    fun `filteredRecentlyAdded emits items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredRecentlyAdded.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        recentlyAddedFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.filteredRecentlyAdded.value)
    }

    @Test
    fun `filteredRecentlyAdded is capped at 50 items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredRecentlyAdded.collect {} }
        recentlyAddedFlow.value = (1..60).map { i -> item("Book $i", "Author") }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50, vm.filteredRecentlyAdded.value.size)
    }

    @Test
    fun `filteredRecentlyAdded when offline filters to only downloaded items`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
        )
        backgroundScope.launch { vm.filteredRecentlyAdded.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        recentlyAddedFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.filteredRecentlyAdded.value)
    }

    // --- toReadItems ---

    @Test
    fun `toReadItems is the intersection of toReadItemIds and allBooks when online`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf("id-Dune", "id-Foundation"))
        val vm = makeViewModel(toReadRepository = toRead)
        backgroundScope.launch { vm.toReadItems.collect {} }
        allBooksFlow.value = listOf(
            item("Dune", "Frank Herbert"),
            item("Foundation", "Isaac Asimov"),
            item("1984", "George Orwell"),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov")),
            vm.toReadItems.value,
        )
    }

    @Test
    fun `toReadItems when offline only includes available-offline items`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf("id-Dune", "id-Foundation"))
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
            toReadRepository = toRead,
        )
        backgroundScope.launch { vm.toReadItems.collect {} }
        allBooksFlow.value = listOf(
            item("Dune", "Frank Herbert"),
            item("Foundation", "Isaac Asimov"),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.toReadItems.value)
    }

    @Test
    fun `init calls toReadRepository refresh`() = runTest {
        val toRead = FakeToReadRepository()
        val vm = makeViewModel(toReadRepository = toRead)
        backgroundScope.launch { vm.isLoading.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(toRead.refreshCount >= 1)
    }

    @Test
    fun `toRead refresh failure does not flip the offline banner`() = runTest {
        val toRead = object : ToReadRepository {
            override fun observeToReadItemIds(libraryId: String) = MutableStateFlow<Set<String>>(emptySet())
            override suspend fun refresh(libraryId: String): Boolean = false
            override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
            override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
            override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
        }
        val vm = makeViewModel(toReadRepository = toRead)
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isOffline.value)
    }

    // --- periodic retry while refresh is failing ---

    private fun countingRepo(
        refreshResult: () -> LibraryRefreshResult,
        onRefreshCall: () -> Unit = {},
    ): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = itemsFlow
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = inProgressFlow
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = finishedFlow
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = recentlyAddedFlow
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooksFlow
        override fun observeSeries(libraryId: String): Flow<List<Series>> = seriesFlow
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collectionsFlow
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
            seriesItemsBySeriesId.getOrPut(seriesId) { MutableStateFlow(emptyList()) }
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
            collectionItemsByCollectionId.getOrPut(collectionId) { MutableStateFlow(emptyList()) }
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
            onRefreshCall(); return refreshResult()
        }
        override suspend fun refreshSeries(libraryId: String) = refreshResult()
        override suspend fun refreshCollections(libraryId: String) = refreshResult()
    }

    @Test
    fun `does not poll while refresh keeps succeeding`() = runTest {
        var refreshCount = 0
        val vm = makeViewModel(
            libraryRepository = countingRepo({ LibraryRefreshResult.Success }) { refreshCount++ },
        )
        backgroundScope.launch { vm.isOffline.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        val baseline = refreshCount
        // Idle for far longer than the retry interval — no extra refreshes should fire.
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(baseline, refreshCount)
    }

    @Test
    fun `polls every 10 seconds while refresh is failing`() = runTest {
        var refreshCount = 0
        var result: LibraryRefreshResult = LibraryRefreshResult.NetworkError(RuntimeException("boom"))
        val vm = makeViewModel(
            libraryRepository = countingRepo({ result }) { refreshCount++ },
        )
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
        val vm = makeViewModel(
            libraryRepository = countingRepo({ LibraryRefreshResult.NetworkError(RuntimeException("boom")) }) { refreshCount++ },
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
        val vm = makeViewModel(
            libraryRepository = countingRepo({ result }) { refreshCount++ },
        )
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

    @Test
    fun `filteredRecentlyAdded when offline cap still applies after filtering`() = runTest {
        val downloadedIds = (1..60).map { "id-Book $it" }.toSet()
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(downloadedIds),
        )
        backgroundScope.launch { vm.filteredRecentlyAdded.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        recentlyAddedFlow.value = (1..60).map { i -> item("Book $i", "Author") }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50, vm.filteredRecentlyAdded.value.size)
    }

    @Test
    fun `isReadaloudLibrary true when the active library has mediaType readaloud`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.isReadaloudLibrary.collect {} }
        librariesFlow.value = listOf(Library("lib-1", "Readalouds", "readaloud", false))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isReadaloudLibrary.value)
    }

    @Test
    fun `isReadaloudLibrary false for a standard ABS library`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.isReadaloudLibrary.collect {} }
        librariesFlow.value = listOf(Library("lib-1", "Books", "book", false))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isReadaloudLibrary.value)
    }

    // --- searchQuery persistence (issue #60) ---

    @Test
    fun `searchQuery is restored from SavedStateHandle on construction`() = runTest {
        val handle = SavedStateHandle(mapOf("libraryId" to "lib-1", "searchQuery" to "dune"))
        val vm = makeViewModel(savedStateHandle = handle)
        backgroundScope.launch { vm.searchQuery.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("dune", vm.searchQuery.value)
    }

    @Test
    fun `onSearchQueryChange writes through to SavedStateHandle`() = runTest {
        val handle = SavedStateHandle(mapOf("libraryId" to "lib-1"))
        val vm = makeViewModel(savedStateHandle = handle)
        backgroundScope.launch { vm.searchQuery.collect {} }
        vm.onSearchQueryChange("foundation")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("foundation", handle.get<String>("searchQuery"))
    }
}
