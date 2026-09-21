package com.riffle.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
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

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSearchViewModel constructor(
    savedStateHandle: SavedStateHandle,
    libraryObserver: LibraryObserver,
    annotationStore: AnnotationStore,
    audiobookBookmarkStore: AudiobookBookmarkStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>(ROUTE_ARG_LIBRARY_ID) ?: ""

    // `urlDecode`, not `java.net.URLDecoder`: the JVM class has no commonMain equivalent, and the
    // shared implementation reproduces its `application/x-www-form-urlencoded` behaviour exactly
    // (`+` → space, `%XX` runs decoded as UTF-8) — which is what Android's nav route produces.
    val query: String = (savedStateHandle.get<String>(ROUTE_ARG_QUERY) ?: "").urlDecode()

    private val _authToken = MutableStateFlow("")
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val libraryItems = libraryObserver.observeLibraryItems(libraryId)

    val results: StateFlow<List<AnnotationSearchResult>> =
        libraryItems
            .flatMapLatest { items ->
                val sourceId = items.firstOrNull()?.sourceId
                if (query.isBlank() || sourceId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    annotationStore.observeAnnotationsForSource(sourceId)
                        .map { annotations -> searchAnnotations(annotations, items, query) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarkResults: StateFlow<List<AudiobookBookmarkSearchResult>> =
        libraryItems
            .flatMapLatest { items ->
                val sourceId = items.firstOrNull()?.sourceId
                if (query.isBlank() || sourceId.isNullOrEmpty()) {
                    flowOf(emptyList())
                } else {
                    audiobookBookmarkStore.observeForSource(sourceId)
                        .map { bookmarks -> searchAudiobookBookmarks(bookmarks, items, query) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val server = sourceRepository.getActive()
            if (server != null) _authToken.value = tokenStorage.getToken(server.id) ?: ""
        }
    }

    companion object {
        // The SavedStateHandle keys, owned by the ViewModel so Android's
        // `annotation_search/{libraryId}?query=…` route and the handle the iOS Koin factory
        // fabricates cannot drift.
        const val ROUTE_ARG_LIBRARY_ID: String = "libraryId"
        const val ROUTE_ARG_QUERY: String = "query"
    }
}
