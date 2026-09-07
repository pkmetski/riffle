package com.riffle.feature.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
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
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

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
    ) = BookshelfViewModel(
        libraryObserver = libraryObserver,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        toReadRepository = toReadRepository,
        annotationsLibraryRepository = annotationsRepo,
    )

    private fun fakeObserver(
        inProgressAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        continueSeriesAllSources: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
    ): LibraryObserver = object : LibraryObserver {
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

    private fun source(id: String) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    // endregion
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
    override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
    override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
    override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
}

private class FakeAllSourcesAnnotationsRepo(
    private val allBooks: List<AnnotatedBook>,
) : AnnotationsLibraryRepository {
    override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
    override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
    override fun observeAnnotatedBooksAllSources(): Flow<List<AnnotatedBook>> = flowOf(allBooks)
}
