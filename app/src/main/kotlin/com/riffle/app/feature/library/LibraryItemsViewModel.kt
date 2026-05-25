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
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    val series: StateFlow<List<Series>> = libraryRepository.observeSeries(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<Collection>> = libraryRepository.observeCollections(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ungroupedItems: StateFlow<List<LibraryItem>> = libraryRepository.observeUngroupedLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val inProgress: StateFlow<List<LibraryItem>> = libraryRepository.observeInProgressItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val finished: StateFlow<List<LibraryItem>> = libraryRepository.observeFinishedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allBooks: StateFlow<List<LibraryItem>> = libraryRepository.observeAllBooks(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allItems: StateFlow<List<LibraryItem>> = libraryRepository.observeLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

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

    val filteredAllBooks: StateFlow<List<LibraryItem>> = combine(allBooks, isOffline) { items, offline ->
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
                .collect { refresh() }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            val itemsDeferred = async { libraryRepository.refreshLibraryItems(libraryId) }
            val seriesDeferred = async { libraryRepository.refreshSeries(libraryId) }
            val collectionsDeferred = async { libraryRepository.refreshCollections(libraryId) }
            val results = listOf(itemsDeferred.await(), seriesDeferred.await(), collectionsDeferred.await())
            _refreshFailed.value = results.any { it is LibraryRefreshResult.NetworkError }
        }
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
}
