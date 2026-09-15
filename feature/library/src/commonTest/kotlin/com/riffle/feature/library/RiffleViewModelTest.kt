package com.riffle.feature.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RiffleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { Dispatchers.resetMain() }

    // region In Progress

    @Test
    fun inProgressEmitsItemsFromAllSourcesObserver() = runTest(dispatcher) {
        val items = listOf(libraryItem("A", "src1"), libraryItem("B", "src2"))
        val observer = fakeObserver(inProgressAllSources = MutableStateFlow(items))
        val vm = makeViewModel(libraryObserver = observer)
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.inProgress.first().map { it.id })
    }

    @Test
    fun inProgressEmitsEmptyListWhenNoItems() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()
        assertTrue(vm.inProgress.first().isEmpty())
    }

    // endregion

    // region Continue Series

    @Test
    fun continueSeriesEmitsItemsFromAllSourcesObserver() = runTest(dispatcher) {
        val items = listOf(libraryItem("X", "src1"), libraryItem("Y", "src2"))
        val observer = fakeObserver(continueSeriesAllSources = MutableStateFlow(items))
        val vm = makeViewModel(libraryObserver = observer)
        advanceUntilIdle()
        assertEquals(listOf("X", "Y"), vm.continueSeries.first().map { it.id })
    }

    // endregion

    // region Annotations

    @Test
    fun annotationsAggregateAcrossAllSources() = runTest(dispatcher) {
        val books = listOf(
            annotatedBook("src1", "item1", latestUpdatedAt = 200),
            annotatedBook("src2", "item2", latestUpdatedAt = 100),
        )
        val repo = FakeAllSourcesAnnotationsRepo(books)
        val vm = makeViewModel(annotationsRepo = repo)
        advanceUntilIdle()
        val result = vm.annotations.first()
        assertEquals(listOf("item1", "item2"), result.map { it.itemId })
    }

    @Test
    fun annotationsEmptyWhenRepoReturnsEmpty() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()
        assertTrue(vm.annotations.first().isEmpty())
    }

    // endregion

    // region To Read refresh

    @Test
    fun toReadRefreshesForKomgaSourcesNotJustAbs() = runTest(dispatcher) {
        // Regression: RiffleViewModel only called refreshForSource for ABS sources, so Komga
        // "To Read" items were never populated in the Riffle unified home screen.
        val komgaSource = source("komga-1", type = SourceType.KOMGA)
        val komgaLibrary = Library(id = "lib-k1", name = "Komga Library", mediaType = "book", isUnsupported = false)
        val tracker = TrackingToReadRepository()
        val observer = fakeObserver(librariesBySourceId = mapOf("komga-1" to listOf(komgaLibrary)))
        val sourceRepo = FakeMultiSourceRepository(listOf(komgaSource))

        makeViewModel(
            libraryObserver = observer,
            sourceRepository = sourceRepo,
            toReadRepository = tracker,
        )
        advanceUntilIdle()

        assertTrue(
            tracker.refreshedPairs.contains("komga-1" to "lib-k1"),
            "refreshForSource must be called for Komga source libraries; got ${tracker.refreshedPairs}",
        )
    }

    // endregion

    // region Offline

    @Test
    fun isOfflineFalseWhenConnected() = runTest(dispatcher) {
        val connectivity = FakeConnectivityObserver(online = true)
        val vm = makeViewModel(connectivity = connectivity)
        advanceUntilIdle()
        assertFalse(vm.isOffline.first())
    }

    @Test
    fun isOfflineTrueWhenDisconnected() = runTest(dispatcher) {
        val connectivity = FakeConnectivityObserver(online = false)
        val vm = makeViewModel(connectivity = connectivity)
        advanceUntilIdle()
        assertTrue(vm.isOffline.first())
    }

    @Test
    fun inProgressFiltersUnavailableItemsWhenOffline() = runTest(dispatcher) {
        val items = listOf(libraryItem("available", "src1"), libraryItem("unavailable", "src2"))
        val observer = fakeObserver(inProgressAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = false),
            offlineAvailability = FakeItemOfflineAvailability(setOf("available")),
        )
        advanceUntilIdle()
        assertEquals(listOf("available"), vm.inProgress.first().map { it.id })
    }

    @Test
    fun inProgressShowsAllItemsWhenOnline() = runTest(dispatcher) {
        val items = listOf(libraryItem("A", "src1"), libraryItem("B", "src2"))
        val observer = fakeObserver(inProgressAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = true),
            offlineAvailability = FakeItemOfflineAvailability(emptySet()),
        )
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.inProgress.first().map { it.id })
    }

    @Test
    fun continueSeriesFiltersUnavailableItemsWhenOffline() = runTest(dispatcher) {
        val items = listOf(libraryItem("kept", "src1"), libraryItem("dropped", "src2"))
        val observer = fakeObserver(continueSeriesAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = false),
            offlineAvailability = FakeItemOfflineAvailability(setOf("kept")),
        )
        advanceUntilIdle()
        assertEquals(listOf("kept"), vm.continueSeries.first().map { it.id })
    }

    @Test
    fun isOfflineTrueWhenRefreshFails() = runTest(dispatcher) {
        // Regression: RiffleViewModel was connectivity-only; a server failure while connected
        // (refreshForSource returns false) must also set isOffline = true.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val observer = fakeObserver(librariesBySourceId = mapOf("abs-1" to listOf(library)))
        val sourceRepo = FakeMultiSourceRepository(listOf(absSource))
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = sourceRepo,
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = FailingToReadRepository(),
        )
        advanceUntilIdle()
        assertTrue(vm.isOffline.first(), "isOffline must be true when a source refresh fails")
    }

    @Test
    fun inProgressFiltersUnavailableItemsWhenRefreshFails() = runTest(dispatcher) {
        // Regression: items that require network must be hidden when the server is unreachable,
        // even if the device still has connectivity.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val items = listOf(libraryItem("cached", "abs-1"), libraryItem("remote-only", "abs-1"))
        val observer = fakeObserver(
            librariesBySourceId = mapOf("abs-1" to listOf(library)),
            inProgressAllSources = MutableStateFlow(items),
        )
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = FailingToReadRepository(),
            offlineAvailability = FakeItemOfflineAvailability(setOf("cached")),
        )
        advanceUntilIdle()
        assertEquals(
            listOf("cached"),
            vm.inProgress.first().map { it.id },
            "inProgress must exclude non-offline-available items when refresh fails",
        )
    }

    @Test
    fun isOfflineFalseAfterSuccessfulRefresh() = runTest(dispatcher) {
        // A successful refresh with connectivity up must keep isOffline = false.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val observer = fakeObserver(librariesBySourceId = mapOf("abs-1" to listOf(library)))
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = FakeToReadRepository(),
        )
        advanceUntilIdle()
        assertFalse(vm.isOffline.first(), "isOffline must remain false when connected and refresh succeeds")
    }

    // endregion

    // region Sources

    @Test
    fun sourcesFlowReflectsSourceRepository() = runTest(dispatcher) {
        val source1 = source("s1")
        val source2 = source("s2")
        val sourceRepo = FakeMultiSourceRepository(listOf(source1, source2))
        val vm = makeViewModel(sourceRepository = sourceRepo)
        advanceUntilIdle()
        val result = vm.sources.first()
        assertEquals(listOf("s1", "s2"), result.map { it.id })
    }

    // endregion

    // region Helpers

    private fun makeViewModel(
        libraryObserver: LibraryObserver = fakeObserver(),
        sourceRepository: SourceRepository = FakeMultiSourceRepository(emptyList()),
        tokenStorage: TokenStorage = fakeTokenStorage(),
        toReadRepository: ToReadRepository = FakeToReadRepository(),
        annotationsRepo: AnnotationsLibraryRepository = FakeAllSourcesAnnotationsRepo(emptyList()),
        connectivity: ConnectivityObserver = FakeConnectivityObserver(online = true),
        offlineAvailability: LibraryItemOfflineAvailability = FakeItemOfflineAvailability(emptySet()),
    ) = RiffleViewModel(
        libraryObserver = libraryObserver,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        toReadRepository = toReadRepository,
        annotationsLibraryRepository = annotationsRepo,
        connectivityObserver = connectivity,
        offlineAvailability = offlineAvailability,
    )

    private fun fakeObserver(
        inProgressAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        continueSeriesAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        librariesBySourceId: Map<String, List<Library>> = emptyMap(),
    ): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> =
            flowOf(librariesBySourceId[sourceId] ?: emptyList())
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
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
        override fun observeInProgressItemsAllSources(): Flow<List<LibraryItem>> = inProgressAllSources
        override fun observeContinueSeriesItemsAllSources(): Flow<List<LibraryItem>> = continueSeriesAllSources
    }

    private fun libraryItem(id: String, sourceId: String) = LibraryItem(
        id = id,
        sourceId = sourceId,
        libraryId = "lib-1",
        title = "Title $id",
        author = "Author",
        coverUrl = null,
        readingProgress = 0.5f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun annotatedBook(sourceId: String, itemId: String, latestUpdatedAt: Long) = AnnotatedBook(
        sourceId = sourceId,
        itemId = itemId,
        title = "Title $itemId",
        author = "Author",
        coverUrl = null,
        highlightCount = 1,
        latestUpdatedAt = latestUpdatedAt,
    )

    private fun source(id: String, type: SourceType = SourceType.ABS) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = type,
    )

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    // endregion
}

private class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(online)
}

private class FakeItemOfflineAvailability(private val availableIds: Set<String>) : LibraryItemOfflineAvailability {
    override fun isAvailableOffline(item: LibraryItem): Boolean = item.id in availableIds
}

private class FakeMultiSourceRepository(initial: List<Source>) : SourceRepository {
    private val sources = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Source>> = sources
    override suspend fun getActive(): Source? = sources.value.firstOrNull { it.isActive }
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(RuntimeException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class FakeToReadRepository : ToReadRepository {
    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh(libraryId: String): Boolean = true
    override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = true
    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
}

private class TrackingToReadRepository : ToReadRepository {
    val refreshedPairs = mutableListOf<Pair<String, String>>()
    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh(libraryId: String): Boolean = true
    override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean {
        refreshedPairs += sourceId to libraryId
        return true
    }
    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
}

private class FailingToReadRepository : ToReadRepository {
    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh(libraryId: String): Boolean = false
    override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = false
    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = false
}

private class FakeAllSourcesAnnotationsRepo(
    private val allBooks: List<AnnotatedBook>,
) : AnnotationsLibraryRepository {
    override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
    override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
    override fun observeAnnotatedBooksAllSources(): Flow<List<AnnotatedBook>> = flowOf(allBooks)
}
