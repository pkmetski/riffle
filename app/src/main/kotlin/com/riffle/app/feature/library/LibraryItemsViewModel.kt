package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.collectReconnects
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.domain.Series
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryItemsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val refreshLibraryItemsUseCase: RefreshLibraryItems,
    private val refreshSeriesUseCase: RefreshSeries,
    private val refreshCollectionsUseCase: RefreshCollections,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val offlineAvailability: LibraryItemOfflineAvailability,
    private val connectivityObserver: ConnectivityObserver,
    private val toReadRepository: ToReadRepository,
    private val playlistsRepository: PlaylistsRepository,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val coverGridDensityStore: com.riffle.core.domain.CoverGridDensityStore,
    private val annotationStore: AnnotationStore,
    private val audiobookBookmarkStore: AudiobookBookmarkStore,
    private val annotationsLibraryRepository: AnnotationsLibraryRepository,
) : ViewModel() {

    val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    /** User's persisted pinch-to-zoom multiplier for the cover grids (1.0 = defaults). */
    val coverGridScale: StateFlow<Float> = coverGridDensityStore.scale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    private var coverScalePersistJob: Job? = null

    fun setCoverGridScale(value: Float) {
        // A pinch fires many events per gesture. Debounce the DataStore write so we
        // persist once the gesture settles instead of hammering it on every frame —
        // this also stops the persisted-scale flow from re-emitting mid-gesture and
        // clobbering the live scale the UI is being driven from.
        coverScalePersistJob?.cancel()
        coverScalePersistJob = viewModelScope.launch {
            delay(200)
            coverGridDensityStore.setScale(value)
        }
    }

    val series: StateFlow<List<Series>> = libraryObserver.observeSeries(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<Collection>> = libraryObserver.observeCollections(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Up to 4 representative cover URLs per Collection, used by [CollectionCoverTile] for a 2×2
     * mosaic. Keyed off the collection-id list (not the full Collection objects) so renames or
     * other field changes on existing collections don't restart every member-items flow.
     */
    val collectionCoverUrls: StateFlow<Map<String, List<String>>> = collections
        .map { cols -> cols.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    ids.map { id ->
                        libraryObserver.observeCollectionItems(id).map { items ->
                            id to items.take(4).mapNotNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } }
                        }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val ungroupedItems: StateFlow<List<LibraryItem>> = libraryObserver.observeUngroupedLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val inProgress: StateFlow<List<LibraryItem>> = libraryObserver.observeInProgressItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val finished: StateFlow<List<LibraryItem>> = libraryObserver.observeFinishedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recentlyAdded: StateFlow<List<LibraryItem>> = libraryObserver.observeRecentlyAddedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val continueSeriesBase: StateFlow<List<LibraryItem>> = libraryObserver.observeContinueSeriesItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allBooks: StateFlow<List<LibraryItem>> = libraryObserver.observeAllBooks(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toReadItemIds: StateFlow<Set<String>> = toReadRepository.observeToReadItemIds(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Set of ABS Library Item ids in this library that have a Readaloud↔ABS link — drives the
     * small readaloud badge on each LibraryItemCard (ADR 0026: readalouds surface only on ABS
     * items).
     */
    val linkedItemIds: StateFlow<Set<String>> = readaloudLinkRepository.observeLinkedAbsItemIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val allItems: StateFlow<List<LibraryItem>> = libraryObserver.observeLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // An audiobooks-only library: every item is a listen-only Audiobook. Drives square covers across
    // every tile in the library — including Series / Collection / "+ N more" tiles that carry no
    // per-item audio signal of their own (ADR 0029). Also gates the Playlists tab (audiobook root
    // only, per the audiobook-playlists design).
    val isAudiobooksOnlyLibrary: StateFlow<Boolean> = allItems
        .map { items -> items.isNotEmpty() && items.all { it.isListenable && !it.isReadable } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val coversAreSquare: StateFlow<Boolean> get() = isAudiobooksOnlyLibrary

    /** Playlists for this library, filtered by [PlaylistsRepository] to hide the reserved "To Read". */
    val playlists: StateFlow<List<CatalogPlaylist>> = playlistsRepository.observePlaylists(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Backed by SavedStateHandle so the query survives both book-detail round-trips and process
    // death (issue #60).
    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    private val _refreshFailed = MutableStateFlow(false)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // The banner appears when the device has no network or when the last server refresh failed.
    val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.isOnline,
        _refreshFailed,
    ) { online, refreshFailed ->
        !online || refreshFailed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _notStartedFilterActive = MutableStateFlow(false)
    val notStartedFilterActive: StateFlow<Boolean> = _notStartedFilterActive.asStateFlow()

    fun toggleNotStartedFilter() {
        _notStartedFilterActive.value = !_notStartedFilterActive.value
    }

    // Share the VM's already-stateIn'd source flows with the engine so we don't open a second
    // set of Room cursors for the same observe* calls. libraryObserver is still passed through
    // for the per-group offline filter, which needs to observe each series'/collection's items.
    private val filterEngine = LibraryFilterEngine(
        libraryObserver = libraryObserver,
        annotationStore = annotationStore,
        audiobookBookmarkStore = audiobookBookmarkStore,
        offlineAvailability = offlineAvailability,
        seriesSource = series,
        collectionsSource = collections,
        ungroupedSource = ungroupedItems,
        inProgressSource = inProgress,
        finishedSource = finished,
        recentlyAddedSource = recentlyAdded,
        continueSeriesSource = continueSeriesBase,
        allBooksSource = allBooks,
        allItemsSource = allItems,
        toReadIdsSource = toReadItemIds,
        isOffline = isOffline,
        searchQuery = searchQuery,
        notStartedFilterActive = _notStartedFilterActive,
    )

    val projection: StateFlow<LibraryProjection> = filterEngine.projection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryProjection.Empty)

    // Books with at least one live highlight in THIS library — the same query the Annotations tab
    // content uses (AnnotationsListViewModel), so tab visibility can't disagree with what the tab
    // would render.
    private val annotatedBooksInLibrary: Flow<List<com.riffle.core.data.AnnotatedBook>> =
        sourceRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { sourceId ->
                if (sourceId == null) flowOf(emptyList())
                else annotationsLibraryRepository.observeAnnotatedBooks(sourceId, libraryId)
            }

    /**
     * Which optional Library tabs are visible. Home and All Books are unconditional; every other
     * tab is shown iff the exact list the tab would render is non-empty. Backed by the same
     * [LibraryProjection] the UI already reads (mapping stale To-Read IDs through the library's
     * items, applying the offline filter, etc.) plus the same annotated-books query
     * `AnnotationsListViewModel` renders — so tab visibility cannot disagree with what the tab
     * content would show.
     *
     * Null while the library hasn't loaded any items yet ([allItems] empty is treated as "not yet
     * settled") — Room's initial `emptyList()` tick from every projection sub-flow would otherwise
     * fire the tab-bar clamp before real data lands and wipe a `rememberSaveable`-restored tab.
     * The UI treats null as "show every tab" so the initial paint is stable and the LaunchedEffect
     * doesn't run.
     */
    val tabVisibility: StateFlow<LibraryTabVisibility?> = combine(
        projection,
        annotatedBooksInLibrary,
        allItems,
        playlists,
        isAudiobooksOnlyLibrary,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val p = values[0] as LibraryProjection
        @Suppress("UNCHECKED_CAST")
        val annotated = values[1] as List<com.riffle.core.data.AnnotatedBook>
        @Suppress("UNCHECKED_CAST")
        val items = values[2] as List<LibraryItem>
        @Suppress("UNCHECKED_CAST")
        val pls = values[3] as List<CatalogPlaylist>
        val audiobookOnly = values[4] as Boolean
        if (items.isEmpty()) {
            null
        } else {
            LibraryTabVisibility(
                toRead = p.toRead.isNotEmpty(),
                series = p.series.isNotEmpty(),
                collections = p.collections.isNotEmpty(),
                annotations = annotated.isNotEmpty(),
                // ABS audiobook root only. The gate is (audiobook library) AND (at least one
                // non-reserved playlist) — the "To Read" filter lives inside PlaylistsRepository so
                // a lib whose only playlist is "To Read" correctly reports playlists=false here.
                playlists = audiobookOnly && pls.isNotEmpty(),
            )
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = sourceRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            val refreshJob = launch { refresh() }
            launch { toReadRepository.refresh(libraryId) }
            launch { playlistsRepository.refresh(libraryId) }
            // Unblock the UI as soon as we have something meaningful to show:
            // either Room returns cached data quickly, or we wait for the network
            // refresh to complete so an empty state is known to be genuine.
            val anyCachedData = merge(
                inProgress.filter { it.isNotEmpty() },
                finished.filter { it.isNotEmpty() },
                allBooks.filter { it.isNotEmpty() },
                series.filter { it.isNotEmpty() },
                collections.filter { it.isNotEmpty() },
            )
            val hasCached = withTimeoutOrNull(500L) { anyCachedData.first() } != null
            if (!hasCached) refreshJob.join()
            _isLoading.value = false
        }
        // Auto-refresh whenever the device returns to online so cached data is refreshed
        // and the offline banner clears without requiring a manual lifecycle resume.
        viewModelScope.launch {
            connectivityObserver.isOnline.collectReconnects {
                refresh()
                toReadRepository.refresh(libraryId)
                playlistsRepository.refresh(libraryId)
            }
        }
        // While a refresh is failing AND the device is online (i.e. server unreachable on an
        // otherwise-healthy network), retry periodically so the banner clears on its own once
        // the server returns. We do NOT poll when the device itself is offline — the
        // on-reconnect listener above already triggers a refresh when connectivity returns —
        // and we do NOT poll in the healthy state, since stale data on screen is still
        // accurate until the user next interacts.
        viewModelScope.launch {
            combine(_refreshFailed, connectivityObserver.isOnline) { failed, online -> failed && online }
                .collectLatest { shouldPoll ->
                    if (shouldPoll) {
                        while (true) {
                            delay(FAILED_REFRESH_RETRY_INTERVAL_MS)
                            runRefresh()
                        }
                    }
                }
        }
    }

    fun onSearchQueryChange(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    /** Called from ON_RESUME. Refreshes so `_refreshFailed` clears the moment the library screen
     * returns to the foreground if the server is back. ConnectivityObserver self-heals its own
     * doze/wake drift on ProcessLifecycleOwner ON_START (see ConnectivityObserverImpl) — no
     * explicit poke needed here. */
    fun onScreenResumed() {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { runRefresh() }
    }

    private suspend fun runRefresh() = coroutineScope {
        val itemsDeferred = async { refreshLibraryItemsUseCase(libraryId) }
        val seriesDeferred = async { refreshSeriesUseCase(libraryId) }
        val collectionsDeferred = async { refreshCollectionsUseCase(libraryId) }
        // ToRead / Playlists refresh runs alongside but its failure must NOT flip the offline banner.
        val toReadDeferred = async { toReadRepository.refresh(libraryId) }
        val playlistsDeferred = async { playlistsRepository.refresh(libraryId) }
        val results = listOf(itemsDeferred.await(), seriesDeferred.await(), collectionsDeferred.await())
        toReadDeferred.await()
        playlistsDeferred.await()
        _refreshFailed.value = results.any { it is LibraryRefreshResult.NetworkError }
    }

    private companion object {
        const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
        const val KEY_SEARCH_QUERY = "searchQuery"
    }
}
