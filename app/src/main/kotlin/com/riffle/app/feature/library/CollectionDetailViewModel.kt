package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
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

    private val allItems: StateFlow<List<LibraryItem>> = libraryRepository.observeCollectionItems(collectionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<LibraryItem>> = combine(allItems, isOffline) { items, offline ->
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
            refresh()
        }
        viewModelScope.launch {
            connectivityObserver.isOnline
                .drop(1)
                .filter { it }
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshFailed.value = libraryRepository.refreshCollections(libraryId) is LibraryRefreshResult.NetworkError
        }
    }

    private fun isAvailableOffline(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.id) || epubRepository.isCached(item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.id) || pdfRepository.isCached(item.id)
        else -> false
    }
}
