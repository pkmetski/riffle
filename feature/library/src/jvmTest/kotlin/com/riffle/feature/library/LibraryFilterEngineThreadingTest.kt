package com.riffle.feature.library

import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.JvmEpubRepository
import com.riffle.core.domain.LibraryItemOfflineAvailabilityImpl
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Threading-specific tests for [LibraryFilterEngine] that require JVM primitives
 * ([java.util.Collections.synchronizedSet], [Thread.currentThread]). These cannot run in
 * commonTest and live here as a companion to [LibraryFilterEngineTest] in commonTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilterEngineThreadingTest {

    private val allBooksFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val isOfflineFlow = MutableStateFlow(false)
    private val searchQueryFlow = MutableStateFlow("")
    private val notStartedFilterFlow = MutableStateFlow(false)
    private val librarySortModeFlow = MutableStateFlow(LibrarySortMode.ADDED_DESC)
    private val toReadIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val seriesFlow = MutableStateFlow<List<Series>>(emptyList())
    private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
    private val ungroupedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val inProgressFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val finishedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val recentlyAddedFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val continueSeriesFlow = MutableStateFlow<List<LibraryItem>>(emptyList())

    private fun fakeRepo(): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = ungroupedFlow
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = inProgressFlow
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = finishedFlow
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = recentlyAddedFlow
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = continueSeriesFlow
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooksFlow
        override fun observeSeries(libraryId: String): Flow<List<Series>> = seriesFlow
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collectionsFlow
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun fakeAnnotationStore(): com.riffle.core.domain.AnnotationStore = object : com.riffle.core.domain.AnnotationStore {
        override fun observeHighlights(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.models.Annotation>())
        override fun observeBookmarks(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.models.Annotation>())
        override fun observeAnnotations(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.models.Annotation>())
        override fun observeAnnotationsForSource(sourceId: String) = MutableStateFlow(emptyList<com.riffle.core.models.Annotation>())
        override suspend fun createHighlight(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double, embeddedFigures: List<com.riffle.core.models.EmbeddedFigure>?, originFontFamily: String, textSnippetHtml: String?) = error("unused")
        override suspend fun createBookmark(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String, originFontFamily: String, fragmentAnchor: String?) = error("unused")
        override suspend fun createImageAnnotation(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, imageHref: String?, imageSvg: String?, imageBytes: String?, color: String) = error("unused")
        override suspend fun upgradeImageToCaptionHighlight(id: String, cfi: String, textSnippet: String, textBefore: String, textAfter: String, figure: com.riffle.core.models.EmbeddedFigure): com.riffle.core.models.Annotation? = null
        override suspend fun mergeFiguresIntoHighlight(id: String, newFigures: List<com.riffle.core.models.EmbeddedFigure>): com.riffle.core.models.Annotation? = null
        override suspend fun delete(id: String) = error("unused")
        override suspend fun recolor(id: String, color: String) = error("unused")
        override suspend fun updateNote(id: String, note: String?) = error("unused")
        override suspend fun renameBookmark(id: String, title: String) = error("unused")
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): com.riffle.core.models.Annotation? = null
        override suspend fun findImageAnnotationForFigure(sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?): com.riffle.core.models.Annotation? = null
        override suspend fun backfillNullOriginFontFamily(sourceId: String, itemId: String, fontFamily: String) = error("unused")
        override suspend fun healSentinelOriginFontFamily(sourceId: String, itemId: String, sentinel: String, fontFamily: String) = error("unused")
    }

    private fun fakeBookmarkStore(): AudiobookBookmarkStore = object : AudiobookBookmarkStore {
        override fun observe(sourceId: String, itemId: String) = MutableStateFlow(emptyList<com.riffle.core.models.AudiobookBookmark>())
        override fun observeForSource(sourceId: String) = MutableStateFlow(emptyList<com.riffle.core.models.AudiobookBookmark>())
        override fun observeHasUnsynced(sourceId: String, itemId: String) = MutableStateFlow(false)
        override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long) = error("unused")
        override suspend fun rename(id: String, title: String, now: Long) = error("unused")
        override suspend fun delete(id: String, now: Long) = error("unused")
    }

    private fun fakePdfRepo(): PdfRepository = object : PdfRepository {
        override suspend fun downloadPdf(item: LibraryItem, onProgress: (Long, Long) -> Unit) = PdfDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {}
    }

    private fun fakeAudiobookDownloadRepo(): AudiobookDownloadRepository = object : AudiobookDownloadRepository {
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(sourceId: String, itemId: String, onProgress: (Long, Long) -> Unit) = AudiobookDownloadResult.Success
        override suspend fun remove(sourceId: String, itemId: String): Long = 0L
    }

    private fun fakeCbzRepo(): CbzRepository = object : CbzRepository {
        override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Offline
        override suspend fun downloadCbz(item: LibraryItem, onProgress: (Long, Long) -> Unit): CbzDownloadResult = CbzDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {}
        override suspend fun supportsStreaming(sourceId: String): Boolean = false
        override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray = error("unused in test")
        override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
    }

    private fun item(title: String) = LibraryItem(
        "id-$title", "lib-1", title, "Author", null, 0f, false, false, EbookFormat.Epub,
    )

    private fun makeEngine(epubRepository: EpubRepository): LibraryFilterEngine = LibraryFilterEngine(
        libraryObserver = fakeRepo(),
        annotationStore = fakeAnnotationStore(),
        audiobookBookmarkStore = fakeBookmarkStore(),
        offlineAvailability = LibraryItemOfflineAvailabilityImpl(
            epubRepository,
            fakePdfRepo(),
            fakeCbzRepo(),
            fakeAudiobookDownloadRepo(),
            object : BundleAudiobookSource {
                override suspend fun localSession(sourceId: String, itemId: String) = null
                override fun isAvailableOffline(sourceId: String, itemId: String) = false
            },
        ),
        seriesSource = seriesFlow,
        collectionsSource = collectionsFlow,
        ungroupedSource = ungroupedFlow,
        inProgressSource = inProgressFlow,
        finishedSource = finishedFlow,
        recentlyAddedSource = recentlyAddedFlow,
        continueSeriesSource = continueSeriesFlow,
        allBooksSource = allBooksFlow,
        allItemsSource = allItemsFlow,
        toReadIdsSource = toReadIdsFlow,
        isOffline = isOfflineFlow,
        searchQuery = searchQueryFlow,
        notStartedFilterActive = notStartedFilterFlow,
        librarySortMode = librarySortModeFlow,
        computeDispatcher = kotlinx.coroutines.Dispatchers.Default,
    )

    // --- threading (page-turn main-thread sweep regression) ---

    @Test
    fun `offline availability sweep runs on the compute dispatcher, never the collector thread`() = runTest {
        val sweepThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val recordingEpubRepo = object : JvmEpubRepository {
            override suspend fun openEpub(item: LibraryItem) = EpubOpenResult.Offline
            override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) = EpubDownloadResult.Success
            override suspend fun removeDownload(sourceId: String, itemId: String) {}
            override fun isDownloaded(sourceId: String, itemId: String): Boolean {
                sweepThreads.add(Thread.currentThread().name)
                return itemId == "id-A"
            }
            override fun isCached(sourceId: String, itemId: String): Boolean = false
            override suspend fun cacheEpub(sourceId: String, itemId: String, bytes: ByteArray) {}
            override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) {}
        }
        val engine = makeEngine(epubRepository = recordingEpubRepo)
        isOfflineFlow.value = true
        allBooksFlow.value = listOf(item("A"), item("B"))

        val collectorThread = Thread.currentThread().name
        val p = engine.projection.first { it.allBooks.map { b -> b.id } == listOf("id-A") }
        Assert.assertEquals(listOf("id-A"), p.allBooks.map { it.id })

        // The whole point of the engine's flowOn: the per-item filesystem sweep must not run on
        // the thread that collects the projection (in production that is Main — a page turn
        // re-emits every source flow, and an on-Main sweep froze the reader for seconds).
        Assert.assertTrue("sweep never ran", sweepThreads.isNotEmpty())
        Assert.assertTrue(
            "sweep ran on the collector thread: $sweepThreads",
            sweepThreads.none { it == collectorThread },
        )
        Assert.assertTrue(
            "expected Dispatchers.Default workers, got $sweepThreads",
            sweepThreads.all { it.contains("DefaultDispatcher") },
        )
    }
}
