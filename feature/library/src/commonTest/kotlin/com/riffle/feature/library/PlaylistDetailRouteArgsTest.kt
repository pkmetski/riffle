package com.riffle.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PlaylistsRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1072 §1 — `PlaylistDetailViewModel` was `:app`-only and read its three arguments from string
 * literals inside its own body. Both hosts now build the handle: Android from the nav route
 * `playlist_detail/{libraryId}/{playlistId}/{playlistName}`, iOS from
 * `playlistSavedStateHandle(...)` in the Koin graph. Nothing fails when those keys drift — the
 * screen just loads an empty playlist with a blank title — so the constants are pinned here.
 *
 * Renaming `ROUTE_ARG_LIBRARY_ID` away from `"libraryId"` (the revert) fails
 * [theRouteArgumentKeysMatchAndroidsNavRoute]; dropping the `urlDecode()` on the name fails
 * [thePlaylistNameArrivesPercentDecoded]; and reading the ids from the wrong keys fails
 * [theViewModelReadsItsIdsFromTheHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailRouteArgsTest {

    // `viewModelScope` runs on Dispatchers.Main; without a scheduler-controlled Main the VM's
    // init block never runs under virtual time and the playlist never loads.
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun theRouteArgumentKeysMatchAndroidsNavRoute() {
        assertEquals("libraryId", PlaylistDetailViewModel.ROUTE_ARG_LIBRARY_ID)
        assertEquals("playlistId", PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_ID)
        assertEquals("playlistName", PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_NAME)
    }

    @Test
    fun theViewModelReadsItsIdsFromTheHandle() = runTest {
        val vm = makeVm(libraryId = "root-9", playlistId = "pl-7")

        assertEquals("root-9", vm.libraryId)
        assertEquals("pl-7", vm.playlistId)
    }

    /**
     * Both hosts hand the name in form-encoded, so the header must show "Evening Queue", not
     * "Evening+Queue".
     */
    @Test
    fun thePlaylistNameArrivesPercentDecoded() = runTest {
        val vm = makeVm(playlistName = "Evening+Queue+%26+more")

        assertEquals("Evening Queue & more", vm.uiState.value.name)
    }

    /** The screen lists the playlist's items in the Source's order, not the library's. */
    @Test
    fun itemsAreOrderedByThePlaylistNotTheLibrary() = runTest {
        val vm = makeVm(
            playlist = CatalogPlaylist(
                id = "pl-7",
                rootId = "root-9",
                name = "Evening Queue",
                bookCount = 2,
                itemIds = listOf("b", "a"),
            ),
            libraryItems = listOf(item("a"), item("b")),
        )
        // Warm the stateIn: the ViewModel's uiState is WhileSubscribed, so it only loads while
        // something collects it.
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val ready = vm.uiState.value
        assertTrue(!ready.isLoading, "expected the playlist to have loaded, got $ready")
        assertEquals(listOf("b", "a"), ready.items.map { it.id })
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun item(id: String) = LibraryItem(
        id = id,
        sourceId = "src-1",
        libraryId = "root-9",
        title = "Title $id",
        author = "",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun makeVm(
        libraryId: String = "root-9",
        playlistId: String = "pl-7",
        playlistName: String = "Evening Queue",
        playlist: CatalogPlaylist? = null,
        libraryItems: List<LibraryItem> = emptyList(),
    ) = PlaylistDetailViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                PlaylistDetailViewModel.ROUTE_ARG_LIBRARY_ID to libraryId,
                PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_ID to playlistId,
                PlaylistDetailViewModel.ROUTE_ARG_PLAYLIST_NAME to playlistName,
            ),
        ),
        playlistsRepository = object : PlaylistsRepository {
            override fun observePlaylists(rootId: String): Flow<List<CatalogPlaylist>> = flowOf(emptyList())
            override suspend fun refresh(rootId: String): Boolean = true
            override suspend fun getPlaylist(rootId: String, playlistId: String): CatalogPlaylist? = playlist
            override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist =
                throw NotImplementedError()
            override suspend fun addItemToPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = true
            override suspend fun removeItemFromPlaylist(rootId: String, playlistId: String, itemId: String): Boolean = true
        },
        libraryObserver = object : LibraryObserver {
            override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
            override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
            override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(libraryItems)
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
            override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
            override suspend fun getItem(itemId: String): LibraryItem? = null
            override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
            override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
            override suspend fun getLibrary(libraryId: String): Library? = null
        },
        sourceRepository = object : SourceRepository {
            override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
            override suspend fun getActive(): Source? = null
            override suspend fun commit(
                pending: com.riffle.core.domain.PendingSource,
                hiddenLibraryIds: Set<String>,
            ): com.riffle.core.domain.CommitSourceResult = throw NotImplementedError()
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        },
        tokenStorage = object : TokenStorage {
            override suspend fun getToken(sourceId: String): String? = null
            override suspend fun saveToken(sourceId: String, token: String) = Unit
            override suspend fun deleteToken(sourceId: String) = Unit
        },
    )
}
