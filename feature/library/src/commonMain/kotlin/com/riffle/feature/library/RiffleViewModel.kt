package com.riffle.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@OptIn(ExperimentalCoroutinesApi::class)
class RiffleViewModel constructor(
    private val libraryObserver: LibraryObserver,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val toReadRepository: ToReadRepository,
    private val annotationsLibraryRepository: AnnotationsLibraryRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val offlineAvailability: LibraryItemOfflineAvailability,
) : ViewModel() {

    // Tracks which sourceIds currently have a failing To Read refresh. A Set (rather than a single
    // Boolean) is necessary because multiple sources refresh concurrently: when one source's library
    // list re-emits and its refreshes succeed, we only clear that source's entry — not the entries
    // of other sources that may still be failing.
    private val _failedSourceIds = MutableStateFlow<Set<String>>(emptySet())

    // The banner appears when the device has no network or when any source's To Read refresh
    // failed — matching the LibraryItemsViewModel parity. Eagerly started so writes from the
    // init refresh loop propagate immediately without waiting for a UI subscriber.
    val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.isOnline,
        _failedSourceIds,
    ) { online, failedIds ->
        !online || failedIds.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val inProgress: StateFlow<List<LibraryItem>> =
        combine(libraryObserver.observeInProgressItemsAllSources(), isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val continueSeries: StateFlow<List<LibraryItem>> =
        combine(libraryObserver.observeContinueSeriesItemsAllSources(), isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Aggregated To Read items across all sources and their libraries. */
    val toRead: StateFlow<List<LibraryItem>> =
        combine(
            sourceRepository.observeAll().flatMapLatest { sources ->
                if (sources.isEmpty()) return@flatMapLatest flowOf(emptyList())
                // Preserve (sourceId, library) pairs so items are queried against their own source's
                // DB rows, not the active source's rows. observeLibraryItems() always scopes to the
                // active source and returns nothing for non-active-source libraries.
                val perSourceLibs = sources.map { source ->
                    libraryObserver.observeLibraries(source.id)
                        .map { libs -> libs.map { source.id to it } }
                }
                combine(perSourceLibs) { arrays -> arrays.flatMap { it } }
                    .flatMapLatest { sourceLibraryPairs ->
                        if (sourceLibraryPairs.isEmpty()) return@flatMapLatest flowOf(emptyList())
                        val perLibrary = sourceLibraryPairs.map { (sourceId, library) ->
                            combine(
                                toReadRepository.observeToReadItemIds(library.id),
                                libraryObserver.observeLibraryItemsForSource(sourceId, library.id),
                            ) { ids, items -> items.filter { it.id in ids } }
                        }
                        combine(perLibrary) { arrays -> arrays.flatMap { it } }
                    }
            },
            isOffline,
        ) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val annotations: StateFlow<List<AnnotatedBook>> =
        annotationsLibraryRepository.observeAnnotatedBooksAllSources()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All configured sources — used by the composable to build the source-name badge map. */
    val sources: StateFlow<List<com.riffle.core.models.Source>> =
        sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Maps sourceId → auth token for authenticated cover image loading. */
    var authTokenMap: Map<String, String> by mutableStateOf(emptyMap())
        private set

    init {
        // Refresh tokens and To Read lists whenever the source list changes.
        // collectLatest cancels the previous block (and all its children) whenever a new emission
        // arrives, preventing coroutine accumulation across source-list changes.
        viewModelScope.launch {
            sourceRepository.observeAll().collectLatest { sources ->
                _failedSourceIds.value = emptySet()
                authTokenMap = coroutineScope {
                    sources.associate { source ->
                        val token = async { tokenStorage.getToken(source.id) ?: "" }
                        source.id to token.await()
                    }
                }
                // supervisorScope keeps this lambda alive (so collectLatest doesn't return
                // prematurely) and cancels all child observers when a new emission arrives.
                supervisorScope {
                    // Refresh To Read for all sources, not just ABS. refreshForSource is a no-op
                    // for sources that don't implement PlaylistsCapability (returns true early).
                    sources.forEach { source ->
                        launch {
                            libraryObserver.observeLibraries(source.id).collectLatest { libraries ->
                                // Clear this source's failure entry before re-refreshing, so a
                                // successful re-emit clears the banner even if a prior pass failed.
                                _failedSourceIds.update { it - source.id }
                                coroutineScope {
                                    libraries.forEach { library ->
                                        launch {
                                            val success = runCatching {
                                                toReadRepository.refreshForSource(source.id, library.id)
                                            }.getOrDefault(false)
                                            if (!success) _failedSourceIds.update { it + source.id }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
