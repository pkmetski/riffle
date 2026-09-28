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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    // Gate inProgress and continueSeries on the combined isOffline signal (connectivity observer
    // AND _failedSourceIds) rather than directly on connectivityObserver.isOnline. On Android 13+
    // the OS can silently drop the onLost callback, leaving isOnline stuck at true for up to 15s.
    // After the refreshForSource fix, _failedSourceIds is non-empty only on genuine network
    // failures — the same condition that makes items unplayable — so isOffline is the correct gate.
    val inProgress: StateFlow<List<LibraryItem>> =
        isOffline.flatMapLatest { offline ->
            if (!offline) {
                libraryObserver.observeInProgressItemsAllSources()
            } else {
                libraryObserver.observeAllLibraryItemsAllSources()
                    .map { items -> items.filter { offlineAvailability.isAvailableOffline(it) } }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val continueSeries: StateFlow<List<LibraryItem>> =
        isOffline.flatMapLatest { offline ->
            if (!offline) {
                libraryObserver.observeContinueSeriesItemsAllSources()
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Aggregated To Read items across all sources and their libraries. */
    val toRead: StateFlow<List<LibraryItem>> =
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
        // Continuously poll while any source is failing and the device is online. On
        // offline→online transition shouldPoll flips to true and retryFailedSources() fires
        // immediately (no separate collectReconnects block needed — collectLatest handles it).
        viewModelScope.launch {
            combine(_failedSourceIds, connectivityObserver.isOnline) { failed, online ->
                failed.isNotEmpty() && online
            }.collectLatest { shouldPoll ->
                if (shouldPoll) {
                    retryFailedSources()
                    while (true) {
                        delay(FAILED_REFRESH_RETRY_INTERVAL_MS)
                        retryFailedSources()
                    }
                }
            }
        }
    }

    private suspend fun retryFailedSources() {
        val failedIds = _failedSourceIds.value
        if (failedIds.isEmpty()) return
        val sources = sourceRepository.observeAll().first().filter { it.id in failedIds }
        supervisorScope {
            sources.forEach { source ->
                launch {
                    val libraries = libraryObserver.observeLibraries(source.id).first()
                    // Only clear the source from the failed set once ALL libraries succeed —
                    // pre-clearing causes a brief banner disappearance on every 10s poll tick.
                    val results = libraries.map { library ->
                        async {
                            runCatching {
                                toReadRepository.refreshForSource(source.id, library.id)
                            }.getOrDefault(false)
                        }
                    }.map { it.await() }
                    if (results.all { it }) _failedSourceIds.update { it - source.id }
                }
            }
        }
    }

    companion object {
        internal const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
    }
}
