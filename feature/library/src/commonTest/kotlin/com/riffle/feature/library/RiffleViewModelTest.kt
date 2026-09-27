package com.riffle.feature.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.ConnectivityObserver
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
import kotlinx.coroutines.test.advanceTimeBy
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
    fun inProgressShowsAllItemsWhenOffline() = runTest(dispatcher) {
        // All items show in Riffle home even when offline — the home is an overview of all sources.
        // The offline gate is at the action level (Read/Listen button greyed out), not item visibility.
        val items = listOf(libraryItem("available", "src1"), libraryItem("unavailable", "src2"))
        val observer = fakeObserver(inProgressAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = false),
        )
        advanceUntilIdle()
        assertEquals(listOf("available", "unavailable"), vm.inProgress.first().map { it.id })
    }

    @Test
    fun inProgressShowsAllItemsWhenOnline() = runTest(dispatcher) {
        val items = listOf(libraryItem("A", "src1"), libraryItem("B", "src2"))
        val observer = fakeObserver(inProgressAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = true),
        )
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.inProgress.first().map { it.id })
    }

    @Test
    fun continueSeriesShowsAllItemsWhenOffline() = runTest(dispatcher) {
        // All items show even when offline — same policy as inProgressShowsAllItemsWhenOffline.
        val items = listOf(libraryItem("kept", "src1"), libraryItem("dropped", "src2"))
        val observer = fakeObserver(continueSeriesAllSources = MutableStateFlow(items))
        val vm = makeViewModel(
            libraryObserver = observer,
            connectivity = FakeConnectivityObserver(online = false),
        )
        advanceUntilIdle()
        assertEquals(listOf("kept", "dropped"), vm.continueSeries.first().map { it.id })
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
    fun inProgressShowsAllItemsWhenRefreshFails() = runTest(dispatcher) {
        // When refresh fails but the device still has network connectivity, the filter must NOT
        // kick in. The item filter is gated on true network loss (!online) only — a server
        // failure with an online device does not make items unplayable; the server may recover.
        // The banner still shows (isOffline=true from _failedSourceIds), but items are unfiltered.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val items = listOf(libraryItem("item1", "abs-1"), libraryItem("item2", "abs-1"))
        val observer = fakeObserver(
            librariesBySourceId = mapOf("abs-1" to listOf(library)),
            inProgressAllSources = MutableStateFlow(items),
        )
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = FailingToReadRepository(),
        )
        advanceUntilIdle()
        assertTrue(vm.isOffline.first(), "banner must show when refresh fails")
        assertEquals(
            listOf("item1", "item2"),
            vm.inProgress.first().map { it.id },
            "all items must show when refresh fails but network is up",
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

    @Test
    fun isOfflineClearsOnReconnect() = runTest(dispatcher) {
        // Regression: RiffleViewModel had no retry on reconnect, so once _failedSourceIds was set
        // it only cleared on a source DB change (rare). On offline→online transition the banner
        // must self-heal by retrying refreshForSource for every previously-failing source.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val observer = fakeObserver(librariesBySourceId = mapOf("abs-1" to listOf(library)))
        val toReadRepo = ToggleableToReadRepository(initialSuccess = false)
        val connectivity = MutableFakeConnectivityObserver(initial = false)
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = connectivity,
            toReadRepository = toReadRepo,
        )
        advanceUntilIdle()
        assertTrue(vm.isOffline.first(), "isOffline must be true when offline and refresh fails")

        toReadRepo.succeeds = true
        connectivity.setOnline(true)
        advanceUntilIdle()

        assertFalse(vm.isOffline.first(), "isOffline must clear when connectivity is restored and retry succeeds")
    }

    @Test
    fun isOfflinePollRetryClearsFailureWhenRefreshEventuallySucceeds() = runTest(dispatcher) {
        // Regression: without the polling retry loop, a failed refresh while online would leave the
        // banner permanently until a source DB change. The polling loop must retry every 10s and
        // clear _failedSourceIds when the server becomes reachable again.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val observer = fakeObserver(librariesBySourceId = mapOf("abs-1" to listOf(library)))
        val toReadRepo = ToggleableToReadRepository(initialSuccess = false)
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = toReadRepo,
        )
        advanceUntilIdle()
        assertTrue(vm.isOffline.first(), "isOffline must be true after initial failing refresh")

        toReadRepo.succeeds = true
        advanceTimeBy(RiffleViewModel.FAILED_REFRESH_RETRY_INTERVAL_MS + 1)
        advanceUntilIdle()

        assertFalse(vm.isOffline.first(), "isOffline must clear after poll retry succeeds")
    }

    @Test
    fun isOfflineClearsWhenLibraryReEmitsAndRefreshSucceeds() = runTest(dispatcher) {
        // Regression: with a single _refreshFailed Boolean, a failed refresh sets the flag but a
        // subsequent successful library re-emit never cleared it — the offline banner stuck
        // permanently. The fix uses a per-source Set so clearing one source's entry doesn't affect
        // others, and the inner collectLatest resets the source's entry before each re-refresh.
        val absSource = source("abs-1", type = SourceType.ABS)
        val library = Library(id = "lib-1", name = "My Library", mediaType = "book", isUnsupported = false)
        val librariesFlow = MutableStateFlow(listOf(library))
        val observer = fakeObserver(librariesFlowBySourceId = mapOf("abs-1" to librariesFlow))
        val toReadRepo = ToggleableToReadRepository(initialSuccess = false)
        val vm = makeViewModel(
            libraryObserver = observer,
            sourceRepository = FakeMultiSourceRepository(listOf(absSource)),
            connectivity = FakeConnectivityObserver(online = true),
            toReadRepository = toReadRepo,
        )
        advanceUntilIdle()
        assertTrue(vm.isOffline.first(), "isOffline must be true after initial failing refresh")

        // Server comes back: flip the repo to succeed and re-emit a structurally different library
        // list (MutableStateFlow deduplicates equal values, so a new Library object is needed to
        // trigger the inner collectLatest and clear the failure entry).
        toReadRepo.succeeds = true
        val libraryReloaded = Library(id = "lib-1", name = "My Library (reloaded)", mediaType = "book", isUnsupported = false)
        librariesFlow.value = listOf(libraryReloaded)
        advanceUntilIdle()

        assertFalse(vm.isOffline.first(), "isOffline must clear when library re-emits and refresh now succeeds")
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
    ) = RiffleViewModel(
        libraryObserver = libraryObserver,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        toReadRepository = toReadRepository,
        annotationsLibraryRepository = annotationsRepo,
        connectivityObserver = connectivity,
    )

    private fun fakeObserver(
        inProgressAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        continueSeriesAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        librariesBySourceId: Map<String, List<Library>> = emptyMap(),
        librariesFlowBySourceId: Map<String, Flow<List<Library>>> = emptyMap(),
    ): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> =
            librariesFlowBySourceId[sourceId] ?: flowOf(librariesBySourceId[sourceId] ?: emptyList())
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

private class MutableFakeConnectivityObserver(initial: Boolean) : ConnectivityObserver {
    private val _isOnline = MutableStateFlow(initial)
    override val isOnline: StateFlow<Boolean> = _isOnline
    fun setOnline(value: Boolean) { _isOnline.value = value }
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

private class ToggleableToReadRepository(initialSuccess: Boolean) : ToReadRepository {
    var succeeds: Boolean = initialSuccess
    override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun refresh(libraryId: String): Boolean = succeeds
    override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = succeeds
    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = succeeds
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = succeeds
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

