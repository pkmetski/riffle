package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.RefreshCollections
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val refreshCollectionsUseCase: RefreshCollections,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val offlineAvailability: LibraryItemOfflineAvailability,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    val collectionId: String = savedStateHandle.get<String>("collectionId") ?: ""
    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    private val _refreshFailed = MutableStateFlow(false)

    val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.isOnline,
        _refreshFailed,
    ) { online, refreshFailed ->
        !online || refreshFailed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val allItems: StateFlow<List<LibraryItem>> = libraryObserver.observeCollectionItems(collectionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<LibraryItem>> = combine(allItems, isOffline) { items, offline ->
        if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = sourceRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            refresh()
        }
        viewModelScope.launch {
            connectivityObserver.isOnline
                .drop(1)
                .filter { it }
                .collect { refresh() }
        }
        // Retry while the last refresh failed AND the device is online (server unreachable on
        // an otherwise-healthy network). When the device itself is offline we skip polling —
        // the on-reconnect listener above already triggers a refresh when connectivity returns.
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

    fun refresh() {
        viewModelScope.launch { runRefresh() }
    }

    private suspend fun runRefresh() {
        _refreshFailed.value = refreshCollectionsUseCase(libraryId) is LibraryRefreshResult.NetworkError
    }

    private companion object {
        const val FAILED_REFRESH_RETRY_INTERVAL_MS = 10_000L
    }
}
