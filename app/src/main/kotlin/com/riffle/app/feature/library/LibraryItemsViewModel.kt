package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.Series
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val toReadRepository: ToReadRepository,
) : ViewModel() {

    val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    // Readaloud Libraries (ADR 0020) collapse the Library Tab Bar to All Books only — Storyteller
    // exposes no Series, Collections, or To Read source.
    val isReadaloudLibrary: StateFlow<Boolean> = libraryRepository.observeLibraries()
        .map { libs -> libs.firstOrNull { it.id == libraryId }?.mediaType == "readaloud" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    private val allBooks: StateFlow<List<LibraryItem>> = libraryRepository.observeAllBooks(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toReadItemIds: StateFlow<Set<String>> = toReadRepository.observeToReadItemIds(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val allItems: StateFlow<List<LibraryItem>> = libraryRepository.observeLibraryItems(libraryId)
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

    val filteredSeries: StateFlow<List<Series>> = combine(series, searchQuery, isOffline) { list, query, offline ->
        Triple(list, query, offline)
    }.flatMapLatest { (list, query, offline) ->
        val queryFiltered = if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
        filterSeriesOffline(queryFiltered, offline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredCollections: StateFlow<List<Collection>> = combine(collections, searchQuery, isOffline) { list, query, offline ->
        Triple(list, query, offline)
    }.flatMapLatest { (list, query, offline) ->
        val queryFiltered = if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
        filterCollectionsOffline(queryFiltered, offline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // When a query is active, search all items (including those in series/collections) so that
    // books are findable by title/author regardless of grouping.
    // When offline, only items available locally (downloaded or cached) are shown.
    val filteredUngroupedItems: StateFlow<List<LibraryItem>> = combine(ungroupedItems, allItems, searchQuery, isOffline) { ungrouped, all, query, offline ->
        val base = if (query.isEmpty()) ungrouped
            else all.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
        if (offline) base.filter { isAvailableOffline(it) } else base
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredInProgress: StateFlow<List<LibraryItem>> = combine(inProgress, isOffline) { items, offline ->
        if (offline) items.filter { isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredFinished: StateFlow<List<LibraryItem>> = combine(finished, isOffline) { items, offline ->
        if (offline) items.filter { isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredRecentlyAdded: StateFlow<List<LibraryItem>> = combine(recentlyAdded, isOffline) { items, offline ->
        val filtered = if (offline) items.filter { isAvailableOffline(it) } else items
        filtered.take(50)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredAllBooks: StateFlow<List<LibraryItem>> = combine(allBooks, isOffline) { items, offline ->
        if (offline) items.filter { isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toReadItems: StateFlow<List<LibraryItem>> = combine(toReadItemIds, allBooks, isOffline) { ids, all, offline ->
        val byId = all.associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }
        if (offline) items.filter { isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    }

    private fun filterCollectionsOffline(collections: List<Collection>, offline: Boolean): Flow<List<Collection>> {
        if (!offline || collections.isEmpty()) return flowOf(collections)
        return combine(collections.map { col -> libraryRepository.observeCollectionItems(col.id) }) { itemArrays ->
            collections.zip(itemArrays.toList())
                .filter { (_, items) -> items.any { isAvailableOffline(it) } }
                .map { (col, _) -> col }
        }
    }

    private fun filterSeriesOffline(series: List<Series>, offline: Boolean): Flow<List<Series>> {
        if (!offline || series.isEmpty()) return flowOf(series)
        return combine(series.map { s -> libraryRepository.observeSeriesItems(s.id) }) { itemArrays ->
            series.zip(itemArrays.toList())
                .filter { (_, items) -> items.any { isAvailableOffline(it) } }
                .map { (s, _) -> s }
        }
    }

    private fun isAvailableOffline(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.id) || epubRepository.isCached(item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.id) || pdfRepository.isCached(item.id)
        else -> false
    }

    private companion object {
        const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
        const val KEY_SEARCH_QUERY = "searchQuery"
    }
}
