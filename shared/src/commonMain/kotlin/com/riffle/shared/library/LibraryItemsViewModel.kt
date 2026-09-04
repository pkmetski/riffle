package com.riffle.shared.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.collectReconnects
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.usecase.RefreshLibraryItems
import com.riffle.core.domain.usecase.RefreshSeries
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemsViewModel constructor(
    val libraryId: String,
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
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val coverGridDensityStore: CoverGridDensityStore,
    private val libraryFilterPreferencesStore: LibraryFilterPreferencesStore,
    private val annotationStore: AnnotationStore,
    private val audiobookBookmarkStore: AudiobookBookmarkStore,
    private val annotationsLibraryRepository: AnnotationsLibraryRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _activeSourceId = MutableStateFlow<String?>(null)
    private val _screenDimensionBucket = MutableStateFlow<ScreenDimensionBucket?>(null)

    val coverGridScale: StateFlow<Float> = combine(_activeSourceId, _screenDimensionBucket) { sourceId, bucket ->
        if (sourceId != null && bucket != null) {
            coverGridDensityStore.scale(sourceId, libraryId, bucket)
        } else {
            coverGridDensityStore.scale
        }
    }
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    private var coverScalePersistJob: Job? = null

    fun setCoverGridScale(value: Float) {
        coverScalePersistJob?.cancel()
        coverScalePersistJob = viewModelScope.launch {
            delay(200)
            val sourceId = _activeSourceId.filterNotNull().first()
            val bucket = _screenDimensionBucket.value
            if (bucket != null) {
                coverGridDensityStore.setScale(sourceId, libraryId, bucket, value)
            } else {
                coverGridDensityStore.setScale(value)
            }
        }
    }

    fun setScreenDimensionBucket(bucket: ScreenDimensionBucket) {
        _screenDimensionBucket.value = bucket
    }

    val series: StateFlow<List<Series>> = libraryObserver.observeSeries(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<Collection>> = libraryObserver.observeCollections(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val linkedItemIds: StateFlow<Set<String>> = readaloudLinkRepository.observeLinkedAbsItemIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val allItems: StateFlow<List<LibraryItem>> = libraryObserver.observeLibraryItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isAudiobooksOnlyLibrary: StateFlow<Boolean> = allItems
        .map { items -> items.isNotEmpty() && items.all { it.isListenable && !it.isReadable } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val coversAreSquare: StateFlow<Boolean> get() = isAudiobooksOnlyLibrary

    val playlists: StateFlow<List<CatalogPlaylist>> = playlistsRepository.observePlaylists(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _refreshFailed = MutableStateFlow(false)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private var refreshJob: Job? = null

    val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.isOnline,
        _refreshFailed,
    ) { online, refreshFailed ->
        !online || refreshFailed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val seriesCoverUrls: StateFlow<Map<String, String>> = series
        .map { rows -> rows.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    ids.map { id ->
                        combine(libraryObserver.observeSeriesItems(id), isOffline) { items, offline ->
                            id to representativeSeriesCoverUrl(items, offline)
                        }
                    },
                ) { pairs ->
                    pairs.mapNotNull { (id, coverUrl) -> coverUrl?.let { id to it } }.toMap()
                }
            }
        }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _notStartedFilterActive = MutableStateFlow(false)
    val notStartedFilterActive: StateFlow<Boolean> = _notStartedFilterActive.asStateFlow()
    private var libraryFilterSourceId: String? = null

    fun toggleNotStartedFilter() {
        val active = !_notStartedFilterActive.value
        _notStartedFilterActive.value = active
        persistNotStartedFilter(active)
    }

    private val _librarySortMode = MutableStateFlow(LibrarySortMode.ADDED_DESC)
    val librarySortMode: StateFlow<LibrarySortMode> = _librarySortMode.asStateFlow()

    fun setLibrarySortMode(mode: LibrarySortMode) {
        _librarySortMode.value = mode
        persistLibrarySortMode(mode)
    }

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
        librarySortMode = _librarySortMode,
        computeDispatcher = dispatchers.default,
    )

    val projection: StateFlow<LibraryProjection> = filterEngine.projection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryProjection.Empty)

    private val annotatedBooksInLibrary: Flow<List<com.riffle.core.data.AnnotatedBook>> =
        sourceRepository.observeAll()
            .map { sources -> sources.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { sourceId ->
                if (sourceId == null) {
                    flowOf(emptyList())
                } else {
                    annotationsLibraryRepository.observeAnnotatedBooks(sourceId, libraryId)
                }
            }

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
                playlists = audiobookOnly && pls.isNotEmpty(),
            )
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var authToken: String by mutableStateOf("")
        private set

    init {
        val refreshJob = launchRefresh()
        viewModelScope.launch {
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
        viewModelScope.launch {
            val source = sourceRepository.getActive()
            if (source != null) {
                val tokenDeferred = async { tokenStorage.getToken(source.id) ?: "" }
                val prefsDeferred = async { libraryFilterPreferencesStore.preferences(source.id, libraryId).first() }
                authToken = tokenDeferred.await()
                libraryFilterSourceId = source.id
                _activeSourceId.value = source.id
                val prefs = prefsDeferred.await()
                _notStartedFilterActive.value = prefs.notStartedFilterActive
                _librarySortMode.value = prefs.sortModeName
                    ?.let { runCatching { LibrarySortMode.valueOf(it) }.getOrNull() }
                    ?: LibrarySortMode.ADDED_DESC
            }
        }
        viewModelScope.launch {
            connectivityObserver.isOnline.collectReconnects {
                launchRefresh(clearStaleFailureIfOnline = true).join()
            }
        }
        viewModelScope.launch {
            combine(_refreshFailed, connectivityObserver.isOnline) { failed, online -> failed && online }
                .collectLatest { shouldPoll ->
                    if (shouldPoll) {
                        launchRefresh().join()
                        while (true) {
                            delay(FAILED_REFRESH_RETRY_INTERVAL_MS)
                            launchRefresh().join()
                        }
                    }
                }
        }
    }

    private fun persistNotStartedFilter(active: Boolean) {
        val sourceId = libraryFilterSourceId ?: return
        viewModelScope.launch {
            libraryFilterPreferencesStore.setNotStartedFilterActive(sourceId, libraryId, active)
        }
    }

    private fun persistLibrarySortMode(mode: LibrarySortMode) {
        val sourceId = libraryFilterSourceId ?: return
        viewModelScope.launch {
            libraryFilterPreferencesStore.setSortModeName(sourceId, libraryId, mode.name)
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onScreenResumed() {
        launchRefresh(clearStaleFailureIfOnline = true)
    }

    fun refresh() {
        launchRefresh()
    }

    private fun launchRefresh(clearStaleFailureIfOnline: Boolean = false): Job {
        if (clearStaleFailureIfOnline && connectivityObserver.isOnline.value) {
            _refreshFailed.value = false
        }
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val job = viewModelScope.launch { runRefresh() }
        refreshJob = job
        job.invokeOnCompletion {
            if (refreshJob === job) refreshJob = null
        }
        return job
    }

    private suspend fun runRefresh() = coroutineScope {
        val itemsDeferred = async { refreshLibraryItemsUseCase(libraryId) }
        val seriesDeferred = async { refreshSeriesUseCase(libraryId) }
        val collectionsDeferred = async { refreshCollectionsUseCase(libraryId) }
        val toReadDeferred = async { toReadRepository.refresh(libraryId) }
        val playlistsDeferred = async { playlistsRepository.refresh(libraryId) }
        val results = listOf(itemsDeferred.await(), seriesDeferred.await(), collectionsDeferred.await())
        toReadDeferred.await()
        playlistsDeferred.await()
        _refreshFailed.value = results.any { it is LibraryRefreshResult.NetworkError }
    }

    private fun representativeSeriesCoverUrl(
        items: List<LibraryItem>,
        offline: Boolean,
    ): String? {
        val preferred = if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        return firstNonBlankCoverUrl(preferred) ?: firstNonBlankCoverUrl(items)
    }

    private companion object {
        const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
    }
}

private fun firstNonBlankCoverUrl(items: List<LibraryItem>): String? =
    items.firstNotNullOfOrNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } }
