package com.riffle.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibrarySectionViewModel constructor(
    private val libraryId: String,
    private val sectionType: LibrarySectionType,
    libraryObserver: LibraryObserver,
    sourceRepository: SourceRepository,
    tokenStorage: TokenStorage,
) : ViewModel() {

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
