package com.riffle.shared.library

import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.models.Annotation
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.EmbeddedFigure as ModelEmbeddedFigure
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test: runRefresh() must absorb exceptions from dependencies rather than letting them
 * propagate to viewModelScope.launch {}, which crashes the process on iOS (no framework-level
 * CoroutineExceptionHandler unlike Android). Reverted try-catch = test fails with uncaught exception.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryItemsViewModelRefreshCrashTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun makeDispatcherProvider(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
    }

    private fun makeLibraryObserver(): LibraryObserver = object : LibraryObserver {
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
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun makeSourceRepository(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            com.riffle.core.domain.CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun makeApplicationScope(): ApplicationScope = object : ApplicationScope {
        private val supervisor = SupervisorJob()
        override val coroutineScope: CoroutineScope = CoroutineScope(supervisor + testDispatcher)
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
            coroutineScope.launch(block = block)
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
            block(coroutineScope)
        override fun scopeOn(dispatcher: CoroutineDispatcher): CoroutineScope =
            CoroutineScope(supervisor + dispatcher)
    }

    private fun makeLibraryRefresher(): LibraryRefresher = object : LibraryRefresher {
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    @Test
    fun refreshFailed_whenDependencyThrows_doesNotPropagateException() = runTest(testDispatcher) {
        val throwingToReadRepo = object : ToReadRepository {
            override fun observeToReadItemIds(libraryId: String): Flow<Set<String>> = flowOf(emptySet())
            override suspend fun refresh(libraryId: String): Boolean =
                throw RuntimeException("simulated network failure")
            override suspend fun isInToRead(libraryItemId: String, libraryId: String): Boolean = false
            override suspend fun addToToRead(libraryItemId: String, libraryId: String): Boolean = true
            override suspend fun removeFromToRead(libraryItemId: String, libraryId: String): Boolean = true
        }

        val noOpPlaylistsRepo = object : PlaylistsRepository {
            override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> = flowOf(emptyList())
            override suspend fun refresh(rootId: String): Boolean = true
            override suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist? = null
            override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist =
                error("not used")
            override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = true
            override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = true
        }

        val appScope = makeApplicationScope()
        val vm = LibraryItemsViewModel(
            libraryId = "lib1",
            libraryObserver = makeLibraryObserver(),
            refreshLibraryItemsUseCase = RefreshLibraryItems(
                refresher = makeLibraryRefresher(),
                storytellerSyncer = object : StorytellerReadaloudCacheSyncer { override suspend fun syncStale() {} },
                readaloudReconciler = object : ReadaloudLinkReconciler { override suspend fun reconcileLinks() {} },
                applicationScope = appScope,
            ),
            refreshSeriesUseCase = RefreshSeries(makeLibraryRefresher()),
            refreshCollectionsUseCase = RefreshCollections(makeLibraryRefresher()),
            sourceRepository = makeSourceRepository(),
            tokenStorage = object : TokenStorage {
                override suspend fun getToken(sourceId: String): String? = null
                override suspend fun saveToken(sourceId: String, token: String) {}
                override suspend fun deleteToken(sourceId: String) {}
            },
            offlineAvailability = object : LibraryItemOfflineAvailability {
                override fun isAvailableOffline(item: LibraryItem): Boolean = false
            },
            connectivityObserver = object : ConnectivityObserver {
                override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
            },
            toReadRepository = throwingToReadRepo,
            playlistsRepository = noOpPlaylistsRepo,
            readaloudLinkRepository = object : ReadaloudLinkRepository {
                override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
                override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
                override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
                override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
                override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
                override suspend fun countForSource(sourceId: String): Int = 0
                override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
            },
            coverGridDensityStore = object : CoverGridDensityStore {
                override val scale: Flow<Float> = flowOf(1f)
                override suspend fun setScale(value: Float) {}
                override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> = flowOf(1f)
                override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {}
            },
            libraryFilterPreferencesStore = object : LibraryFilterPreferencesStore {
                override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
                    flowOf(LibraryFilterPreferences())
                override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {}
                override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
                override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
                override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {}
            },
            annotationStore = object : AnnotationStore {
                override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
                override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
                override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
                override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = flowOf(emptyList())
                override fun observeEmphasis(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
                override suspend fun createHighlight(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double, embeddedFigures: List<ModelEmbeddedFigure>?, originFontFamily: String, textSnippetHtml: String?): Annotation = error("not used")
                override suspend fun createBookmark(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String, originFontFamily: String, fragmentAnchor: String?): Annotation = error("not used")
                override suspend fun createImageAnnotation(sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String, spineIndex: Int, progression: Double, imageHref: String?, imageSvg: String?, imageBytes: String?, color: String): Annotation = error("not used")
                override suspend fun backfillNullOriginFontFamily(sourceId: String, itemId: String, fontFamily: String): Int = 0
                override suspend fun healSentinelOriginFontFamily(sourceId: String, itemId: String, sentinel: String, fontFamily: String): Int = 0
                override suspend fun upgradeImageToCaptionHighlight(id: String, cfi: String, textSnippet: String, textBefore: String, textAfter: String, figure: ModelEmbeddedFigure): Annotation? = null
                override suspend fun mergeFiguresIntoHighlight(id: String, newFigures: List<ModelEmbeddedFigure>): Annotation? = null
                override suspend fun delete(id: String) {}
                override suspend fun recolor(id: String, color: String) {}
                override suspend fun updateNote(id: String, note: String?) {}
                override suspend fun renameBookmark(id: String, title: String) {}
                override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
                override suspend fun findImageAnnotationForFigure(sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?): Annotation? = null
            },
            audiobookBookmarkStore = object : AudiobookBookmarkStore {
                override fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
                override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
                override fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean> = flowOf(false)
                override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String = ""
                override suspend fun rename(id: String, title: String, now: Long) {}
                override suspend fun delete(id: String, now: Long) {}
            },
            annotationsLibraryRepository = object : AnnotationsLibraryRepository {
                override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
                override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
            },
            dispatchers = makeDispatcherProvider(),
        )

        // Advance until the refresh coroutine launched in init has completed.
        advanceUntilIdle()

        // If the try-catch in runRefresh() is removed, the exception from toReadRepository.refresh()
        // escapes viewModelScope.launch {}, causing this runTest to fail with an uncaught exception.
        // With the fix, the exception is absorbed and isOffline reflects the failure state.
        assertTrue(vm.isOffline.value, "expected isOffline=true when refresh throws (refreshFailed=true, online=true)")
    }
}
