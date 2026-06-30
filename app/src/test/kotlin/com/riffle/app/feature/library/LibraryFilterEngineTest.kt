package com.riffle.app.feature.library

import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.Collection
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilterEngineTest {

    private val seriesFlow = MutableStateFlow<List<Series>>(emptyList())
    private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
    private val ungroupedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val inProgressFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val finishedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val recentlyAddedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val continueSeriesFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allBooksFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val seriesItemsBySeriesId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val collectionItemsByCollectionId = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()
    private val annotationsFlow = MutableStateFlow<List<com.riffle.core.domain.Annotation>>(emptyList())
    private val bookmarksFlow = MutableStateFlow<List<com.riffle.core.domain.AudiobookBookmark>>(emptyList())

    private val isOfflineFlow = MutableStateFlow(false)
    private val searchQueryFlow = MutableStateFlow("")
    private val notStartedFilterFlow = MutableStateFlow(false)
    private val toReadIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    private fun fakeRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(serverId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = ungroupedFlow
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
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
    }

    private fun fakeAnnotationStore(): AnnotationStore = object : AnnotationStore {
        override fun observeHighlights(serverId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
        override fun observeBookmarks(serverId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
        override fun observeAnnotations(serverId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.Annotation>())
        override fun observeAnnotationsForServer(serverId: String) =
            annotationsFlow.map { all -> all.filter { it.serverId == serverId } }
        override suspend fun createHighlight(serverId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double) = error("unused")
        override suspend fun createBookmark(serverId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String) = error("unused")
        override suspend fun delete(id: String) = error("unused")
        override suspend fun recolor(id: String, color: String) = error("unused")
        override suspend fun updateNote(id: String, note: String?) = error("unused")
        override suspend fun renameBookmark(id: String, title: String) = error("unused")
        override suspend fun findByItemAndCfi(serverId: String, itemId: String, cfi: String): com.riffle.core.domain.Annotation? = null
    }

    private fun fakeBookmarkStore(): AudiobookBookmarkStore = object : AudiobookBookmarkStore {
        override fun observe(serverId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.domain.AudiobookBookmark>())
        override fun observeForServer(serverId: String) = bookmarksFlow.map { all -> all.filter { it.serverId == serverId } }
        override fun observeHasUnsynced(serverId: String, itemId: String) = MutableStateFlow(false)
        override suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long) = error("unused")
        override suspend fun rename(id: String, title: String, now: Long) = error("unused")
        override suspend fun delete(id: String, now: Long) = error("unused")
    }

    private class FakeToReadRepository(initial: Set<String> = emptySet()) : ToReadRepository {
        val ids = MutableStateFlow(initial)
        override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = ids
        override suspend fun refresh(libraryId: String): Boolean = true
        override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = libraryItemId in ids.value
        override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean { ids.value = ids.value + libraryItemId; return true }
        override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean { ids.value = ids.value - libraryItemId; return true }
    }

    private fun epubRepoWithDownloads(downloadedIds: Set<String>): EpubRepository = object : EpubRepository {
        override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) = EpubDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = itemId in downloadedIds
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private fun fakePdfRepo(): PdfRepository = object : PdfRepository {
        override suspend fun openPdf(item: LibraryItem) = PdfOpenResult.Offline
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit) = PdfDownloadResult.Success
        override suspend fun removeDownload(serverId: String, itemId: String) {}
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun isCached(serverId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
    }

    private fun fakeAudiobookDownloadRepo(): AudiobookDownloadRepository = object : AudiobookDownloadRepository {
        override fun isDownloaded(serverId: String, itemId: String): Boolean = false
        override fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(serverId: String, itemId: String, onProgress: (Long, Long) -> Unit) = AudiobookDownloadResult.Success
        override suspend fun remove(serverId: String, itemId: String): Long = 0L
    }

    private fun makeEngine(
        epubRepository: EpubRepository = epubRepoWithDownloads(emptySet()),
        toReadRepository: ToReadRepository = FakeToReadRepository(),
        annotationStore: AnnotationStore = fakeAnnotationStore(),
        audiobookBookmarkStore: AudiobookBookmarkStore = fakeBookmarkStore(),
    ): LibraryFilterEngine = LibraryFilterEngine(
        libraryRepository = fakeRepo(),
        annotationStore = annotationStore,
        audiobookBookmarkStore = audiobookBookmarkStore,
        toReadRepository = toReadRepository,
        offlineAvailability = LibraryItemOfflineAvailability(
            epubRepository,
            fakePdfRepo(),
            fakeAudiobookDownloadRepo(),
            object : BundleAudiobookSource {
                override suspend fun localSession(serverId: String, itemId: String) = null
                override fun isAvailableOffline(serverId: String, itemId: String) = false
            },
        ),
        libraryId = "lib-1",
        isOffline = isOfflineFlow,
        searchQuery = searchQueryFlow,
        notStartedFilterActive = notStartedFilterFlow,
    )

    private fun series(name: String) = Series("id-$name", "lib-1", name, null, 1)
    private fun collection(name: String) = Collection("id-$name", "lib-1", name, 1)
    private fun item(title: String, author: String = "Author") = LibraryItem(
        "id-$title", "lib-1", title, author, null, 0f, false, false, EbookFormat.Epub,
    )

    private fun itemWithServerId(id: String, title: String, serverId: String) = LibraryItem(
        id = id, libraryId = "lib-1", title = title, author = "A", coverUrl = null,
        readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Epub, serverId = serverId,
    )

    private fun annotation(id: String, serverId: String, itemId: String, snippet: String) =
        com.riffle.core.domain.Annotation(
            id = id, serverId = serverId, itemId = itemId, type = "highlight",
            cfi = "", color = "yellow", note = null, textSnippet = snippet,
            textBefore = "", textAfter = "", chapterHref = "", spineIndex = 0,
            progression = 0.0, bookmarkTitle = "", createdAt = 0L, updatedAt = 0L,
        )

    private fun bookmark(id: String, serverId: String, itemId: String, title: String) =
        com.riffle.core.domain.AudiobookBookmark(
            id = id, serverId = serverId, itemId = itemId, positionSec = 0.0,
            title = title, createdAt = 0L,
        )

    // --- series ---

    @Test
    fun `series passes through when no query, online`() = runTest {
        val engine = makeEngine()
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        val p = engine.projection.first { it.series.isNotEmpty() }
        assertEquals(listOf(series("Mistborn"), series("Stormlight")), p.series)
    }

    @Test
    fun `series query-filters by name case-insensitive`() = runTest {
        val engine = makeEngine()
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        searchQueryFlow.value = "STORM"
        val p = engine.projection.first { it.series.isNotEmpty() }
        assertEquals(listOf(series("Stormlight")), p.series)
    }

    @Test
    fun `series offline-filter drops groups with no offline items`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(emptySet()))
        seriesFlow.value = listOf(series("Mistborn"))
        seriesItemsBySeriesId.getOrPut("id-Mistborn") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Final Empire"))
        isOfflineFlow.value = true
        val p = engine.projection.first()
        assertEquals(emptyList<Series>(), p.series)
    }

    // --- collections ---

    @Test
    fun `collections query-filters by name`() = runTest {
        val engine = makeEngine()
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        searchQueryFlow.value = "sci"
        val p = engine.projection.first { it.collections.isNotEmpty() }
        assertEquals(listOf(collection("Sci-Fi")), p.collections)
    }

    @Test
    fun `collections offline-filter drops empties`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(setOf("id-Mistborn")))
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        collectionItemsByCollectionId.getOrPut("id-Fantasy") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Mistborn"))
        collectionItemsByCollectionId.getOrPut("id-Sci-Fi") { MutableStateFlow(emptyList()) }.value =
            listOf(item("Dune"))
        isOfflineFlow.value = true
        val p = engine.projection.first { it.collections.isNotEmpty() }
        assertEquals(listOf(collection("Fantasy")), p.collections)
    }

    // --- ungrouped ---

    @Test
    fun `ungrouped uses ungrouped source when query blank`() = runTest {
        val engine = makeEngine()
        ungroupedFlow.value = listOf(item("Dune"))
        allItemsFlow.value = listOf(item("Dune"), item("Foundation"))
        val p = engine.projection.first { it.ungrouped.isNotEmpty() }
        assertEquals(listOf(item("Dune")), p.ungrouped)
    }

    @Test
    fun `ungrouped searches allItems by title or author when query active`() = runTest {
        val engine = makeEngine()
        allItemsFlow.value = listOf(item("Dune", "Herbert"), item("Mistborn", "Sanderson"))
        searchQueryFlow.value = "sand"
        val p = engine.projection.first { it.ungrouped.isNotEmpty() }
        assertEquals(listOf(item("Mistborn", "Sanderson")), p.ungrouped)
    }

    @Test
    fun `ungrouped offline-filter drops unavailable`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(setOf("id-Dune")))
        ungroupedFlow.value = listOf(item("Dune"), item("Foundation"))
        isOfflineFlow.value = true
        val p = engine.projection.first { it.ungrouped.isNotEmpty() }
        assertEquals(listOf(item("Dune")), p.ungrouped)
    }

    // --- inProgress / finished ---

    @Test
    fun `inProgress offline-filter`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(setOf("id-Dune")))
        inProgressFlow.value = listOf(item("Dune"), item("Foundation"))
        isOfflineFlow.value = true
        val p = engine.projection.first { it.inProgress.isNotEmpty() }
        assertEquals(listOf(item("Dune")), p.inProgress)
    }

    @Test
    fun `finished offline-filter`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(setOf("id-Dune")))
        finishedFlow.value = listOf(item("Dune"), item("Foundation"))
        isOfflineFlow.value = true
        val p = engine.projection.first { it.finished.isNotEmpty() }
        assertEquals(listOf(item("Dune")), p.finished)
    }

    // --- recentlyAdded ---

    @Test
    fun `recentlyAdded caps at 50`() = runTest {
        val engine = makeEngine()
        recentlyAddedFlow.value = (1..60).map { item("Book $it") }
        val p = engine.projection.first { it.recentlyAdded.isNotEmpty() }
        assertEquals(50, p.recentlyAdded.size)
    }

    // --- continueSeries ---

    @Test
    fun `continueSeries caps at 20`() = runTest {
        val engine = makeEngine()
        continueSeriesFlow.value = (1..30).map { item("B$it") }
        val p = engine.projection.first { it.continueSeries.isNotEmpty() }
        assertEquals(20, p.continueSeries.size)
    }

    // --- allBooks ---

    @Test
    fun `allBooks notStartedFilter composes with offline`() = runTest {
        val engine = makeEngine(epubRepository = epubRepoWithDownloads(setOf("id-Dune")))
        allBooksFlow.value = listOf(
            LibraryItem("id-Dune", "lib-1", "Dune", "H", null, 0f, false, false, EbookFormat.Epub),
            LibraryItem("id-Foundation", "lib-1", "Foundation", "A", null, 0f, false, false, EbookFormat.Epub),
            LibraryItem("id-Martian", "lib-1", "Martian", "W", null, 0.5f, false, false, EbookFormat.Epub),
        )
        isOfflineFlow.value = true
        notStartedFilterFlow.value = true
        val p = engine.projection.first { it.allBooks.isNotEmpty() }
        assertEquals(
            listOf(LibraryItem("id-Dune", "lib-1", "Dune", "H", null, 0f, false, false, EbookFormat.Epub)),
            p.allBooks,
        )
    }

    // --- toRead ---

    @Test
    fun `toRead intersects ids with allBooks then offline-filters`() = runTest {
        val engine = makeEngine(
            epubRepository = epubRepoWithDownloads(setOf("id-Dune")),
            toReadRepository = FakeToReadRepository(setOf("id-Dune", "id-Foundation")),
        )
        allBooksFlow.value = listOf(item("Dune"), item("Foundation"), item("Other"))
        isOfflineFlow.value = true
        val p = engine.projection.first { it.toRead.isNotEmpty() }
        assertEquals(listOf(item("Dune")), p.toRead)
    }

    // --- annotations ---

    @Test
    fun `annotations are empty when query blank`() = runTest {
        val engine = makeEngine()
        allItemsFlow.value = listOf(itemWithServerId("b1", "Dune", "srv1"))
        annotationsFlow.value = listOf(annotation("a1", "srv1", "b1", "spice"))
        val p = engine.projection.first()
        assertEquals(emptyList<AnnotationSearchResult>(), p.annotations)
    }

    @Test
    fun `annotations match query scoped to library items`() = runTest {
        val engine = makeEngine()
        allItemsFlow.value = listOf(itemWithServerId("b1", "Dune", "srv1"))
        annotationsFlow.value = listOf(
            annotation("a1", "srv1", "b1", "conscience matters"),
            annotation("a2", "srv1", "b1", "irrelevant"),
            annotation("aX", "srv1", "NOT_IN_LIB", "conscience"),
        )
        searchQueryFlow.value = "conscience"
        val results = engine.projection.first { it.annotations.isNotEmpty() }.annotations
        assertEquals(listOf("a1"), results.map { it.annotation.id })
    }

    // --- audiobook bookmarks ---

    @Test
    fun `audiobookBookmarks empty when query blank`() = runTest {
        val engine = makeEngine()
        allItemsFlow.value = listOf(itemWithServerId("b1", "Dune", "srv1"))
        bookmarksFlow.value = listOf(bookmark("bm1", "srv1", "b1", "great line"))
        val p = engine.projection.first()
        assertEquals(emptyList<AudiobookBookmarkSearchResult>(), p.audiobookBookmarks)
    }

    @Test
    fun `audiobookBookmarks match query by title`() = runTest {
        val engine = makeEngine()
        allItemsFlow.value = listOf(itemWithServerId("b1", "Dune", "srv1"))
        bookmarksFlow.value = listOf(
            bookmark("bm1", "srv1", "b1", "great line"),
            bookmark("bm2", "srv1", "b1", "irrelevant"),
        )
        searchQueryFlow.value = "great"
        val results = engine.projection.first { it.audiobookBookmarks.isNotEmpty() }.audiobookBookmarks
        assertEquals(listOf("bm1"), results.map { it.bookmark.id })
    }
}
