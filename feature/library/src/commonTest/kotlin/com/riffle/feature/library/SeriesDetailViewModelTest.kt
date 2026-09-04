package com.riffle.feature.library

import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class SeriesDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val onlineState = MutableStateFlow(true)

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun stubRefresher() = object : com.riffle.core.domain.LibraryRefresher {
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private fun item(id: String, cached: Boolean = false) = LibraryItem(
        id = id,
        libraryId = "lib1",
        title = "Title $id",
        author = "",
        coverUrl = null,
        readingProgress = 0f,
        isCached = cached,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun makeVm(
        seriesItems: List<LibraryItem> = emptyList(),
        offlineItems: Set<String> = emptySet(),
    ) = SeriesDetailViewModel(
        seriesId = "series1",
        libraryId = "lib1",
        libraryObserver = object : LibraryObserver {
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
            override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(seriesItems)
            override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
            override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
            override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
            override suspend fun getItem(itemId: String): LibraryItem? = null
            override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
            override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
            override suspend fun getLibrary(libraryId: String): Library? = null
        },
        refreshSeriesUseCase = object : RefreshSeries(stubRefresher()) {},
        sourceRepository = object : SourceRepository {
            override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
            override suspend fun getActive(): Source? = null
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>): com.riffle.core.domain.CommitSourceResult = throw NotImplementedError()
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        },
        tokenStorage = object : TokenStorage {
            override suspend fun getToken(sourceId: String): String? = null
            override suspend fun saveToken(sourceId: String, token: String) = Unit
            override suspend fun deleteToken(sourceId: String) = Unit
        },
        offlineAvailability = object : LibraryItemOfflineAvailability {
            override fun isAvailableOffline(item: LibraryItem): Boolean = item.id in offlineItems
        },
        connectivityObserver = object : ConnectivityObserver {
            override val isOnline: StateFlow<Boolean> get() = onlineState
        },
    )

    @Test
    fun itemsEmittedFromObserveSeriesItems() = runTest {
        val items = listOf(item("a"), item("b"))
        val vm = makeVm(seriesItems = items)
        assertEquals(items, vm.items.first())
    }

    @Test
    fun isOfflineFalseWhenOnlineAndRefreshSucceeds() = runTest {
        val vm = makeVm()
        assertFalse(vm.isOffline.first())
    }

    @Test
    fun isOfflineTrueWhenConnectionLost() = runTest {
        onlineState.value = false
        val vm = makeVm()
        assertTrue(vm.isOffline.first())
    }

    @Test
    fun offlineFiltersToOnlyAvailableItems() = runTest {
        onlineState.value = false
        val items = listOf(item("a", cached = true), item("b"))
        val vm = makeVm(seriesItems = items, offlineItems = setOf("a"))
        assertEquals(listOf(item("a", cached = true)), vm.items.first())
    }

}
