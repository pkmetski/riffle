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
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.Series
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    private val libraryRepository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val offlineAvailability: LibraryItemOfflineAvailability,
    private val connectivityObserver: ConnectivityObserver,
    private val toReadRepository: ToReadRepository,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val coverGridDensityStore: com.riffle.core.domain.CoverGridDensityStore,
    private val annotationStore: AnnotationStore,
    private val audiobookBookmarkStore: AudiobookBookmarkStore,
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

    val series: StateFlow<List<Series>> = libraryRepository.observeSeries(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<Collection>> = libraryRepository.observeCollections(libraryId)
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
                        libraryRepository.observeCollectionItems(id).map { items ->
                            id to items.take(4).mapNotNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } }
                        }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val ungroupedItems: StateFlow<List<LibraryItem>> = libraryRepository.observeUngroupedLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val inProgress: StateFlow<List<LibraryItem>> = libraryRepository.observeInProgressItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val finished: StateFlow<List<LibraryItem>> = libraryRepository.observeFinishedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recentlyAdded: StateFlow<List<LibraryItem>> = libraryRepository.observeRecentlyAddedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val continueSeriesBase: StateFlow<List<LibraryItem>> = libraryRepository.observeContinueSeriesItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allBooks: StateFlow<List<LibraryItem>> = libraryRepository.observeAllBooks(libraryId)
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

    private val allItems: StateFlow<List<LibraryItem>> = libraryRepository.observeLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // An audiobooks-only library: every item is a listen-only Audiobook. Drives square covers across
    // every tile in the library — including Series / Collection / "+ N more" tiles that carry no
    // per-item audio signal of their own (ADR 0029).
    val coversAreSquare: StateFlow<Boolean> = allItems
        .map { items -> items.isNotEmpty() && items.all { it.isListenable && !it.isReadable } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    // set of Room cursors for the same observe* calls. libraryRepository is still passed through
    // for the per-group offline filter, which needs to observe each series'/collection's items.
    private val filterEngine = LibraryFilterEngine(
        libraryRepository = libraryRepository,
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

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            val refreshJob = launch { refresh() }
            launch { toReadRepository.refresh(libraryId) }
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
            connectivityObserver.isOnline
                .drop(1)
                .filter { it }
                .collect {
                    refresh()
                    toReadRepository.refresh(libraryId)
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

    private var lastRefreshCompletedAt = 0L

    /** Called from ON_RESUME. Skips if a refresh completed within the last 30s to avoid a
     * redundant network round-trip when the library screen first enters RESUMED state immediately
     * after init (which already launched its own refresh). */
    fun onScreenResumed() {
        if (System.currentTimeMillis() - lastRefreshCompletedAt > RESUME_REFRESH_DEBOUNCE_MS) {
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch { runRefresh() }
    }

    private suspend fun runRefresh() = coroutineScope {
        val itemsDeferred = async { libraryRepository.refreshLibraryItems(libraryId) }
        val seriesDeferred = async { libraryRepository.refreshSeries(libraryId) }
        val collectionsDeferred = async { libraryRepository.refreshCollections(libraryId) }
        // ToRead refresh runs alongside but its failure must NOT flip the offline banner.
        val toReadDeferred = async { toReadRepository.refresh(libraryId) }
        val results = listOf(itemsDeferred.await(), seriesDeferred.await(), collectionsDeferred.await())
        toReadDeferred.await()
        _refreshFailed.value = results.any { it is LibraryRefreshResult.NetworkError }
        lastRefreshCompletedAt = System.currentTimeMillis()
    }

    private companion object {
        const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
        const val KEY_SEARCH_QUERY = "searchQuery"
        const val RESUME_REFRESH_DEBOUNCE_MS = 30_000L
    }
}
