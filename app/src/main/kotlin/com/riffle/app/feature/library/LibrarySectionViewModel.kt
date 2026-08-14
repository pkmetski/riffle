package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibrarySectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryObserver: LibraryObserver,
    sourceRepository: SourceRepository,
    tokenStorage: TokenStorage,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""
    private val sectionType: LibrarySectionType = savedStateHandle
        .get<String>("sectionType")
        ?.let { runCatching { LibrarySectionType.valueOf(it) }.getOrNull() }
        ?: LibrarySectionType.IN_PROGRESS

    val items: StateFlow<List<LibraryItem>> = librarySectionItems(
        libraryObserver = libraryObserver,
        libraryId = libraryId,
        sectionType = sectionType,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val coversAreSquare: StateFlow<Boolean> = libraryObserver.observeLibraryItems(libraryId)
        .map { items -> items.isNotEmpty() && items.all { it.isListenable && !it.isReadable } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
            if (source != null) {
                authToken = tokenStorage.getToken(source.id) ?: ""
            }
        }
    }
}

internal fun librarySectionItems(
    libraryObserver: LibraryObserver,
    libraryId: String,
    sectionType: LibrarySectionType,
): Flow<List<LibraryItem>> = when (sectionType) {
    LibrarySectionType.IN_PROGRESS -> libraryObserver.observeInProgressItems(libraryId)
    LibrarySectionType.FINISHED -> libraryObserver.observeFinishedItems(libraryId)
    LibrarySectionType.RECENTLY_ADDED -> libraryObserver.observeRecentlyAddedItems(libraryId)
    LibrarySectionType.CONTINUE_SERIES -> libraryObserver.observeContinueSeriesItems(libraryId)
}
