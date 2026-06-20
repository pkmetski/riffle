package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnnotationSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    annotationStore: AnnotationStore,
    audiobookBookmarkStore: AudiobookBookmarkStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""
    val query: String = savedStateHandle.get<String>("query")
        ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    private val _authToken = MutableStateFlow("")
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val libraryItems = libraryRepository.observeLibraryItems(libraryId)

    val results: StateFlow<List<AnnotationSearchResult>> =
        libraryItems
            .flatMapLatest { items ->
                val serverId = items.firstOrNull()?.serverId
                if (query.isBlank() || serverId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    annotationStore.observeAnnotationsForServer(serverId)
                        .map { annotations -> searchAnnotations(annotations, items, query) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarkResults: StateFlow<List<AudiobookBookmarkSearchResult>> =
        libraryItems
            .flatMapLatest { items ->
                val serverId = items.firstOrNull()?.serverId
                if (query.isBlank() || serverId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    audiobookBookmarkStore.observeForServer(serverId)
                        .map { bookmarks -> searchAudiobookBookmarks(bookmarks, items, query) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) _authToken.value = tokenStorage.getToken(server.id) ?: ""
        }
    }
}
