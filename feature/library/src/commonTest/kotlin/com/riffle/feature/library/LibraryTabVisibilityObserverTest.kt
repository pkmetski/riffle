package com.riffle.feature.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [LibraryTabVisibilityObserver] — the source-agnostic feed screens use to hide the
 * To Read / Series / Collections / Annotations tabs when their underlying lists are empty.
 * Regression coverage for the bottom-nav bug where the To Read and Annotations tabs stayed
 * visible on Chitanka/Gutenberg even when both lists were empty; the fix is a shared observer
 * both web-source screens (and any future one) consume via `LibraryTabVisibilityViewModel`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTabVisibilityObserverTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val activeSource = Source(
        id = "src-1",
        url = SourceUrl.parse("https://example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )

    @Test
    fun `everything empty hides every optional tab`() = runTest(dispatcher) {
        val observer = buildObserver()
        assertEquals(LibraryTabVisibility.Empty, awaitVisibility(observer))
    }

    @Test
    fun `to-read only visible when a queued id also has a library row`() = runTest(dispatcher) {
        // A queued id whose library_items row hasn't been upserted must NOT show the tab — the
        // tab content itself filters against `observeAllBooks`, so leaving it visible produces a
        // dead tab with nothing in it.
        val observer = buildObserver(
            toReadIds = MutableStateFlow(setOf("book-1")),
            allBooks = MutableStateFlow(emptyList()),
        )
        assertEquals(false, awaitVisibility(observer).toRead)

        val observerWithRow = buildObserver(
            toReadIds = MutableStateFlow(setOf("book-1")),
            allBooks = MutableStateFlow(listOf(libItem("book-1"))),
        )
        assertEquals(true, awaitVisibility(observerWithRow).toRead)
    }

    @Test
    fun `series tab visibility mirrors observeSeries`() = runTest(dispatcher) {
        val observer = buildObserver(series = MutableStateFlow(listOf(series("s-1"))))
        assertEquals(true, awaitVisibility(observer).series)
    }

    @Test
    fun `collections tab visibility mirrors observeCollections`() = runTest(dispatcher) {
        val observer = buildObserver(collections = MutableStateFlow(listOf(collection("c-1"))))
        assertEquals(true, awaitVisibility(observer).collections)
    }

    @Test
    fun `annotations tab visible when the active source has any annotated book`() = runTest(dispatcher) {
        val observer = buildObserver(annotated = MutableStateFlow(listOf(annotatedBook("book-1"))))
        assertEquals(true, awaitVisibility(observer).annotations)
    }

    @Test
    fun `annotations tab hidden when no source is active`() = runTest(dispatcher) {
        // No active source ⇒ no annotations query is issued, so the tab can never show — even if
        // an annotated-books flow was set up. Pins the null-active-source branch.
        val observer = buildObserver(
            active = null,
            annotated = MutableStateFlow(listOf(annotatedBook("book-1"))),
        )
        assertEquals(false, awaitVisibility(observer).annotations)
    }

    // ---- helpers -----------------------------------------------------------------

    private fun TestScope.awaitVisibility(observer: LibraryTabVisibilityObserver): LibraryTabVisibility {
        var latest: LibraryTabVisibility = LibraryTabVisibility.Empty
        val job = (this as CoroutineScope).launch {
            observer.observe(LIB).collect { latest = it }
        }
        advanceUntilIdle()
        job.cancel()
        return latest
    }

    private fun libItem(id: String) = LibraryItem(
        id = id,
        libraryId = LIB,
        title = id,
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = "src-1",
    )

    private fun series(id: String) =
        Series(id = id, libraryId = LIB, name = id, coverUrl = null, bookCount = 1)

    private fun collection(id: String) =
        Collection(id = id, libraryId = LIB, name = id, bookCount = 1)

    private fun annotatedBook(itemId: String) = AnnotatedBook(
        sourceId = "src-1",
        itemId = itemId,
        title = itemId,
        author = "A",
        coverUrl = null,
        highlightCount = 1,
        latestUpdatedAt = 0L,
    )

    private fun fakeSourceRepo(active: Source?): SourceRepository = object : SourceRepository {
        override fun observeAll() = flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) { }
        override suspend fun remove(sourceId: String) { }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    /**
     * Stands in for the mockk-relaxed [LibraryObserver] the JVM-only version of this test used.
     * Only the three streams the observer reads are configurable; everything else returns an
     * empty flow so the fake compiles against the full interface.
     */
    private class FakeLibraryObserver(
        private val allBooks: Flow<List<LibraryItem>>,
        private val series: Flow<List<Series>>,
        private val collections: Flow<List<Collection>>,
    ) : LibraryObserver {
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooks
        override fun observeSeries(libraryId: String): Flow<List<Series>> = series
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collections
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private class FakeToReadRepository(private val ids: Flow<Set<String>>) : ToReadRepository {
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = ids
        override suspend fun refresh(libraryId: String): Boolean = true
        override suspend fun refreshForSource(sourceId: String, libraryId: String): Boolean = true
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
    }

    private class FakeAnnotationsLibraryRepository(
        private val annotated: Flow<List<AnnotatedBook>>,
    ) : AnnotationsLibraryRepository {
        override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = annotated
        override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> =
            annotated
    }

    private fun buildObserver(
        active: Source? = activeSource,
        toReadIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        allBooks: MutableStateFlow<List<LibraryItem>> = MutableStateFlow(emptyList()),
        series: MutableStateFlow<List<Series>> = MutableStateFlow(emptyList()),
        collections: MutableStateFlow<List<Collection>> = MutableStateFlow(emptyList()),
        annotated: MutableStateFlow<List<AnnotatedBook>> = MutableStateFlow(emptyList()),
    ): LibraryTabVisibilityObserver = LibraryTabVisibilityObserver(
        libraryObserver = FakeLibraryObserver(allBooks, series, collections),
        toReadRepository = FakeToReadRepository(toReadIds),
        annotationsLibraryRepository = FakeAnnotationsLibraryRepository(annotated),
        sourceRepository = fakeSourceRepo(active),
    )

    private companion object {
        const val LIB = "library-1"
    }
}
