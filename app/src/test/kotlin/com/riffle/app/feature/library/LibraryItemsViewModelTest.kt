package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val continueSeriesFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allBooksFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val collectionItemsByCollectionId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val seriesItemsBySeriesId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())
    private val annotationsFlow = MutableStateFlow<List<com.riffle.core.domain.Annotation>>(emptyList())

    private fun fakeAnnotationStore(): com.riffle.core.domain.AnnotationStore =
        object : com.riffle.core.domain.AnnotationStore {
            override fun observeHighlights(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
            override fun observeBookmarks(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
            override fun observeAnnotations(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
            override fun observeAnnotationsForSource(sourceId: String) =
                annotationsFlow.map { all -> all.filter { it.sourceId == sourceId } }
            override suspend fun createHighlight(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double, embeddedFigures: List<com.riffle.core.domain.EmbeddedFigure>?) = error("unused")
            override suspend fun createBookmark(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String) = error("unused")
            override suspend fun createImageAnnotation(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, imageHref: String?, imageSvg: String?, imageBytes: String?, color: String) = error("unused")
            override suspend fun delete(id: String) = error("unused")
            override suspend fun recolor(id: String, color: String) = error("unused")
            override suspend fun updateNote(id: String, note: String?) = error("unused")
            override suspend fun renameBookmark(id: String, title: String) = error("unused")
            override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): com.riffle.core.domain.Annotation? = null
            override suspend fun findImageAnnotationForFigure(
                sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
            ): com.riffle.core.domain.Annotation? = null
        }

    private fun fakeAudiobookBookmarkStore(): com.riffle.core.domain.AudiobookBookmarkStore =
        object : com.riffle.core.domain.AudiobookBookmarkStore {
            override fun observe(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.AudiobookBookmark>())
            override fun observeForSource(sourceId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.AudiobookBookmark>())
            override fun observeHasUnsynced(sourceId: String, itemId: String) = MutableStateFlow(false)
            override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long) = error("unused")
            override suspend fun rename(id: String, title: String, now: Long) = error("unused")
            override suspend fun delete(id: String, now: Long) = error("unused")
        }

    private fun fakeRepo(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = itemsFlow
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = inProgressFlow
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = finishedFlow
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = recentlyAddedFlow
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = continueSeriesFlow
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooksFlow
        override fun observeSeries(libraryId: String): Flow<List<Series>> = seriesFlow
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collectionsFlow
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
            seriesItemsBySeriesId.getOrPut(seriesId) { MutableStateFlow(emptyList()) }
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
            collectionItemsByCollectionId.getOrPut(collectionId) { MutableStateFlow(emptyList()) }
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun fakeServerRepo(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = null
        override suspend fun deleteToken(sourceId: String) {}
    }

    private fun fakeEpubRepo(): EpubRepository = object : EpubRepository {
        override suspend fun openEpub(item: LibraryItem): EpubOpenResult = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit): EpubDownloadResult = EpubDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private fun fakePdfRepo(): PdfRepository = object : PdfRepository {
        override suspend fun openPdf(item: LibraryItem): PdfOpenResult = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit): PdfDownloadResult = PdfDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private fun fakeAudiobookDownloadRepo(downloadedIds: Set<String> = emptySet()): AudiobookDownloadRepository =
        object : AudiobookDownloadRepository {
            override fun isDownloaded(sourceId: String, itemId: String): Boolean = itemId in downloadedIds
            override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
            override suspend fun download(sourceId: String, itemId: String, onProgress: (Long, Long) -> Unit): AudiobookDownloadResult =
                AudiobookDownloadResult.Success
            override suspend fun remove(sourceId: String, itemId: String): Long = 0L
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
        audiobookDownloadRepository: AudiobookDownloadRepository = fakeAudiobookDownloadRepo(),
        libraryRepository: LibraryObserver = fakeRepo(),
        refreshLibraryItemsUseCase: com.riffle.core.domain.usecase.RefreshLibraryItems =
            com.riffle.app.testing.NoopRefreshLibraryItems(),
        sourceRepository: SourceRepository = fakeServerRepo(),
        tokenStorage: TokenStorage = fakeTokenStorage(),
        toReadRepository: ToReadRepository = FakeToReadRepository(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("libraryId" to "lib-1")),
        readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository = NoopReadaloudLinkRepository,
        coverGridDensityStore: com.riffle.core.domain.CoverGridDensityStore = object : com.riffle.core.domain.CoverGridDensityStore {
            override val scale = kotlinx.coroutines.flow.flowOf(1f)
            override suspend fun setScale(value: Float) {}
        },
        annotationStore: com.riffle.core.domain.AnnotationStore = fakeAnnotationStore(),
        audiobookBookmarkStore: com.riffle.core.domain.AudiobookBookmarkStore = fakeAudiobookBookmarkStore(),
    ) = LibraryItemsViewModel(
        savedStateHandle = savedStateHandle,
        libraryObserver = libraryRepository,
        refreshLibraryItemsUseCase = refreshLibraryItemsUseCase,
        refreshSeriesUseCase = com.riffle.app.testing.NoopRefreshSeries(),
        refreshCollectionsUseCase = com.riffle.app.testing.NoopRefreshCollections(),
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        offlineAvailability = LibraryItemOfflineAvailability(
            epubRepository,
            pdfRepository,
            audiobookDownloadRepository,
            object : BundleAudiobookSource {
                override suspend fun localSession(sourceId: String, itemId: String) = null
                override fun isAvailableOffline(sourceId: String, itemId: String) = false
            },
        ),
        connectivityObserver = connectivityObserver,
        toReadRepository = toReadRepository,
        readaloudLinkRepository = readaloudLinkRepository,
        coverGridDensityStore = coverGridDensityStore,
        annotationStore = annotationStore,
        audiobookBookmarkStore = audiobookBookmarkStore,
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
        backgroundScope.launch { vm.projection.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Mistborn"), series("Stormlight")), vm.projection.value.series)
    }

    @Test
    fun `empty query returns all collections`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy"), collection("Sci-Fi")), vm.projection.value.collections)
    }

    @Test
    fun `empty query returns all ungrouped items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov")),
            vm.projection.value.ungrouped,
        )
    }

    // --- series filtering ---

    @Test
    fun `query filters series by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight Archive"))
        vm.onSearchQueryChange("storm")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Stormlight Archive")), vm.projection.value.series)
    }

    @Test
    fun `series filtering is case-insensitive`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        seriesFlow.value = listOf(series("The Wheel of Time"))
        vm.onSearchQueryChange("WHEEL")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("The Wheel of Time")), vm.projection.value.series)
    }

    // --- collection filtering ---

    @Test
    fun `query filters collections by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        vm.onSearchQueryChange("sci")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Sci-Fi")), vm.projection.value.collections)
    }

    // --- item filtering ---

    @Test
    fun `query filters items by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        vm.onSearchQueryChange("dun")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.projection.value.ungrouped)
    }

    @Test
    fun `query filters items by author`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        allItemsFlow.value = listOf(item("Mistborn", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("sand")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Mistborn", "Brandon Sanderson")), vm.projection.value.ungrouped)
    }

    @Test
    fun `query finds books that belong to series by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        // allItemsFlow contains items in series; itemsFlow (ungrouped) does not
        allItemsFlow.value = listOf(item("The Final Empire", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("final empire")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("The Final Empire", "Brandon Sanderson")), vm.projection.value.ungrouped)
    }

    // --- no match ---

    @Test
    fun `query with no match empties all filtered lists`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch {
            vm.projection.collect {}
            vm.projection.collect {}
            vm.projection.collect {}
        }
        seriesFlow.value = listOf(series("Mistborn"))
        collectionsFlow.value = listOf(collection("Fantasy"))
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("zzznomatch")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Series>(), vm.projection.value.series)
        assertEquals(emptyList<Collection>(), vm.projection.value.collections)
        assertEquals(emptyList<LibraryItem>(), vm.projection.value.ungrouped)
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
        backgroundScope.launch { vm.projection.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        inProgressFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.projection.value.inProgress)
    }

    // --- C2: filteredFinished flow ---

    @Test
    fun `filteredFinished emits items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        val expected = listOf(item("1984", "George Orwell"))
        finishedFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.projection.value.finished)
    }

    // --- C3: filteredAllBooks flow ---

    @Test
    fun `filteredAllBooks emits all items from repository when online`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("1984", "George Orwell"), item("Foundation", "Isaac Asimov"))
        allBooksFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.projection.value.allBooks)
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
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) = EpubDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = itemId in downloadedIds
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    @Test
    fun `when offline ungrouped items are filtered to only downloaded`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.projection.value.ungrouped)
        assertEquals(true, vm.isOffline.value)
    }

    @Test
    fun `when offline a downloaded audiobook-only item is shown`() = runTest {
        val audiobook = LibraryItem(
            "id-The Martian", "lib-1", "The Martian", "Andy Weir", null, 0f, false, false,
            EbookFormat.Unsupported, hasAudio = true,
        )
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
            audiobookDownloadRepository = fakeAudiobookDownloadRepo(setOf("id-The Martian")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        itemsFlow.value = listOf(audiobook, item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(audiobook), vm.projection.value.ungrouped)
    }

    @Test
    fun `when online no offline filter is applied to ungrouped items`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = true),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
        )
        backgroundScope.launch { vm.projection.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.projection.value.ungrouped.size)
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
        backgroundScope.launch { vm.projection.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        collectionItemsByCollectionId.getOrPut("id-Fantasy") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Mistborn", "Brandon Sanderson"))
        collectionItemsByCollectionId.getOrPut("id-Sci-Fi") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Dune", "Frank Herbert"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Collection>(), vm.projection.value.collections)
    }

    @Test
    fun `when offline collections with at least one available offline item are shown`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Mistborn")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        collectionItemsByCollectionId.getOrPut("id-Fantasy") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Mistborn", "Brandon Sanderson"))
        collectionItemsByCollectionId.getOrPut("id-Sci-Fi") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Dune", "Frank Herbert"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy")), vm.projection.value.collections)
    }

    @Test
    fun `when offline series with no available offline items are hidden`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(emptySet()),
        )
        backgroundScope.launch { vm.projection.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        seriesItemsBySeriesId.getOrPut("id-Mistborn") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Final Empire", "Brandon Sanderson"))
        seriesItemsBySeriesId.getOrPut("id-Stormlight") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Way of Kings", "Brandon Sanderson"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Series>(), vm.projection.value.series)
    }

    @Test
    fun `when offline series with at least one available offline item are shown`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-The Way of Kings")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        seriesItemsBySeriesId.getOrPut("id-Mistborn") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Final Empire", "Brandon Sanderson"))
        seriesItemsBySeriesId.getOrPut("id-Stormlight") { MutableStateFlow(emptyList()) }.value =
            listOf(item("The Way of Kings", "Brandon Sanderson"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Stormlight")), vm.projection.value.series)
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
            sourceRepository = object : SourceRepository {
                override fun observeAll(): Flow<List<Source>> = MutableStateFlow(emptyList())
                override suspend fun getActive() = Source("srv-1", SourceUrl.parse("http://localhost")!!, true, false, "")
                override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
                    throw UnsupportedOperationException()
                override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
                    throw UnsupportedOperationException()
                override suspend fun setActive(sourceId: String) {}
                override suspend fun remove(sourceId: String) {}
                override suspend fun getSourceVersion(sourceId: String): String? = null
            },
            tokenStorage = object : TokenStorage {
                override suspend fun saveToken(sourceId: String, token: String) {}
                override suspend fun getToken(sourceId: String) = "tok-abc"
                override suspend fun deleteToken(sourceId: String) {}
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
        backgroundScope.launch { vm.projection.collect {} }
        val expected = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        recentlyAddedFlow.value = expected
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(expected, vm.projection.value.recentlyAdded)
    }

    @Test
    fun `filteredRecentlyAdded is capped at 50 items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        recentlyAddedFlow.value = (1..60).map { i -> item("Book $i", "Author") }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50, vm.projection.value.recentlyAdded.size)
    }

    @Test
    fun `filteredRecentlyAdded when offline filters to only downloaded items`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        recentlyAddedFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.projection.value.recentlyAdded)
    }

    // --- toReadItems ---

    @Test
    fun `toReadItems is the intersection of toReadItemIds and allBooks when online`() = runTest {
        val toRead = FakeToReadRepository(initial = setOf("id-Dune", "id-Foundation"))
        val vm = makeViewModel(toReadRepository = toRead)
        backgroundScope.launch { vm.projection.collect {} }
        allBooksFlow.value = listOf(
            item("Dune", "Frank Herbert"),
            item("Foundation", "Isaac Asimov"),
            item("1984", "George Orwell"),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov")),
            vm.projection.value.toRead,
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
        backgroundScope.launch { vm.projection.collect {} }
        allBooksFlow.value = listOf(
            item("Dune", "Frank Herbert"),
            item("Foundation", "Isaac Asimov"),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.projection.value.toRead)
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

    private class CountingRefreshLibraryItems(
        val refreshResult: () -> LibraryRefreshResult,
        val onCall: () -> Unit = {},
    ) : com.riffle.core.domain.usecase.RefreshLibraryItems(
        com.riffle.app.testing.NoopLibraryRefresher,
        object : com.riffle.core.domain.StorytellerReadaloudCacheSyncer {
            override suspend fun syncStale() = Unit
        },
        object : com.riffle.core.domain.ReadaloudLinkReconciler {
            override suspend fun reconcileLinks() = Unit
        },
        com.riffle.app.testing.TestApplicationScope(kotlinx.coroutines.GlobalScope),
    ) {
        override suspend fun invoke(libraryId: String): LibraryRefreshResult {
            onCall(); return refreshResult()
        }
    }

    @Test
    fun `does not poll while refresh keeps succeeding`() = runTest {
        var refreshCount = 0
        val vm = makeViewModel(
            refreshLibraryItemsUseCase = CountingRefreshLibraryItems({ LibraryRefreshResult.Success }) { refreshCount++ },
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
            refreshLibraryItemsUseCase = CountingRefreshLibraryItems({ result }) { refreshCount++ },
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
            refreshLibraryItemsUseCase = CountingRefreshLibraryItems({ LibraryRefreshResult.NetworkError(RuntimeException("boom")) }) { refreshCount++ },
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
            refreshLibraryItemsUseCase = CountingRefreshLibraryItems({ result }) { refreshCount++ },
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
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        recentlyAddedFlow.value = (1..60).map { i -> item("Book $i", "Author") }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50, vm.projection.value.recentlyAdded.size)
    }

    // --- notStartedFilterActive ---

    @Test
    fun `notStartedFilterActive starts as false`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.notStartedFilterActive.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.notStartedFilterActive.value)
    }

    @Test
    fun `toggleNotStartedFilter flips active to true`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.notStartedFilterActive.collect {} }
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.notStartedFilterActive.value)
    }

    @Test
    fun `toggleNotStartedFilter flips active back to false on second call`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.notStartedFilterActive.collect {} }
        vm.toggleNotStartedFilter()
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.notStartedFilterActive.value)
    }

    @Test
    fun `filteredAllBooks shows only zero-progress items when notStartedFilterActive`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.notStartedFilterActive.collect {} }
        allBooksFlow.value = listOf(
            LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
            LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
            LibraryItem("id-Wool", "lib-1", "Wool", "Howey", null, 1f, false, false, EbookFormat.Epub),
        )
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub)),
            vm.projection.value.allBooks,
        )
    }

    @Test
    fun `filteredAllBooks shows all items when notStartedFilter is toggled back off`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        val all = listOf(
            LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
            LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
        )
        allBooksFlow.value = all
        vm.toggleNotStartedFilter()
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(all, vm.projection.value.allBooks)
    }

    @Test
    fun `filteredAllBooks not-started filter composes with offline filter`() = runTest {
        val vm = makeViewModel(
            connectivityObserver = FakeConnectivityObserver(online = false),
            epubRepository = fakeEpubRepoWithDownloads(setOf("id-Dune")),
        )
        backgroundScope.launch { vm.projection.collect {} }
        backgroundScope.launch { vm.isOffline.collect {} }
        allBooksFlow.value = listOf(
            // not started AND downloaded — should appear
            LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub),
            // not started but NOT downloaded — offline filter removes it
            LibraryItem("id-Foundation", "lib-1", "Foundation", "Asimov", null, 0f, false, false, EbookFormat.Epub),
            // in progress AND downloaded — not-started filter removes it
            LibraryItem("id-Martian", "lib-1", "The Martian", "Weir", null, 0.42f, false, false, EbookFormat.Epub),
        )
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(LibraryItem("id-Dune", "lib-1", "Dune", "Herbert", null, 0f, false, false, EbookFormat.Epub)),
            vm.projection.value.allBooks,
        )
    }

    @Test
    fun `filteredAllBooks includes zero-progress audiobook-only items when notStartedFilterActive`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        val audiobook = LibraryItem(
            "id-Audiobook", "lib-1", "Project Hail Mary", "Weir", null, 0f, false, false,
            EbookFormat.Unsupported, hasAudio = true,
        )
        allBooksFlow.value = listOf(
            audiobook,
            LibraryItem("id-Started", "lib-1", "Dune", "Herbert", null, 0.1f, false, false, EbookFormat.Epub),
        )
        vm.toggleNotStartedFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(audiobook), vm.projection.value.allBooks)
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

    // --- continueSeriesItems ---

    @Test
    fun `continueSeriesItems reflects repository emissions`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }

        val nextBook = item("Abaddon's Gate", "James S. A. Corey")
        continueSeriesFlow.value = listOf(nextBook)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(nextBook), vm.projection.value.continueSeries)
    }

    @Test
    fun `continueSeriesItems filters to offline-available items when offline`() = runTest {
        val availableItem = item("Offline Book", "Author A")
        val unavailableItem = item("Online Only", "Author B")
        val connectivity = FakeConnectivityObserver(online = false)
        val epubRepo = object : EpubRepository by fakeEpubRepo() {
            override fun isCached(sourceId: String, itemId: String): Boolean = itemId == availableItem.id
        }
        val vm = makeViewModel(connectivityObserver = connectivity, epubRepository = epubRepo)
        backgroundScope.launch { vm.projection.collect {} }

        continueSeriesFlow.value = listOf(availableItem, unavailableItem)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(availableItem), vm.projection.value.continueSeries)
    }

    // --- filteredAnnotations ---

    private fun annotation(
        id: String,
        sourceId: String,
        itemId: String,
        textSnippet: String = "",
        note: String? = null,
        bookmarkTitle: String = "",
    ) = com.riffle.core.domain.Annotation(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        type = "highlight",
        cfi = "",
        color = "yellow",
        note = note,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = bookmarkTitle,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun itemWithServerId(id: String, title: String, sourceId: String) = LibraryItem(
        id = id,
        libraryId = "lib1",
        title = title,
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        sourceId = sourceId,
    )

    @Test
    fun `filteredAnnotations matches query scoped to library items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        allItemsFlow.value = listOf(itemWithServerId("b1", "Children of Dune", "srv1"))
        annotationsFlow.value = listOf(
            annotation(id = "a1", sourceId = "srv1", itemId = "b1", textSnippet = "conscience is flexible"),
            annotation(id = "a2", sourceId = "srv1", itemId = "b1", textSnippet = "unrelated"),
            annotation(id = "aX", sourceId = "srv1", itemId = "NOT_IN_LIB", textSnippet = "conscience"),
        )
        vm.onSearchQueryChange("conscience")

        val results = vm.projection.first { it.annotations.isNotEmpty() }.annotations
        assertEquals(listOf("a1"), results.map { it.annotation.id })
        assertEquals("Children of Dune", results.single().bookTitle)
    }

    @Test
    fun `filteredAnnotations returns empty list when query is blank`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.projection.collect {} }
        allItemsFlow.value = listOf(itemWithServerId("b1", "Dune", "srv1"))
        annotationsFlow.value = listOf(
            annotation(id = "a1", sourceId = "srv1", itemId = "b1", textSnippet = "spice"),
        )
        // No query set — blank by default
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<AnnotationSearchResult>(), vm.projection.value.annotations)
    }

    // Regression: on ON_RESUME we always refresh so `_refreshFailed` clears if the server is back.
    // Connectivity self-heals inside the ConnectivityObserver via ProcessLifecycleOwner + the
    // ACTION_AIRPLANE_MODE_CHANGED broadcast, so it does not need to be poked from the view model.
    @Test
    fun `onScreenResumed refreshes`() = runTest {
        val toRead = FakeToReadRepository()
        val refreshItems = com.riffle.app.testing.NoopRefreshLibraryItems()
        val vm = makeViewModel(
            toReadRepository = toRead,
            refreshLibraryItemsUseCase = refreshItems,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        val refreshBefore = refreshItems.calls

        vm.onScreenResumed()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(refreshItems.calls > refreshBefore)
    }
}
