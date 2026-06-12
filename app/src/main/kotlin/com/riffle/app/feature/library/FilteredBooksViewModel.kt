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
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/**
 * Backs the [FilteredBooksScreen]: lists every Library Item in the current Library matching one
 * metadata facet (ADR 0027). The filter runs locally over the already-synced items, so it works
 * offline; when offline, results are further narrowed to locally-available books, mirroring the
 * Series/Collection detail screens.
 */
@HiltViewModel
class FilteredBooksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val offlineAvailability: LibraryItemOfflineAvailability,
    private val connectivityObserver: ConnectivityObserver,
    readaloudLinkRepository: ReadaloudLinkRepository,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""
    val facetType: FacetType = runCatching {
        FacetType.valueOf(savedStateHandle.get<String>("facetType") ?: "")
    }.getOrDefault(FacetType.AUTHOR)
    val facetValue: String = URLDecoder.decode(savedStateHandle.get<String>("facetValue") ?: "", "UTF-8")

    val isOffline: StateFlow<Boolean> = connectivityObserver.isOnline
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val items: StateFlow<List<LibraryItem>> = combine(
        libraryRepository.observeLibraryItems(libraryId),
        readaloudLinkRepository.observeLinkedAbsItemIds(),
        connectivityObserver.isOnline,
    ) { all, linkedIds, online ->
        val matched = all.filter { facetMatches(it, facetType, facetValue, linkedIds) }
        if (!online) matched.filter { offlineAvailability.isAvailableOffline(it) } else matched
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
        }
    }
}
