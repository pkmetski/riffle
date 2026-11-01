package com.riffle.feature.source.ui.websource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class OReillyHomeViewModel(
    libraryId: String,
    libraryObserver: LibraryObserver,
) : ViewModel() {

    val inProgress: StateFlow<List<LibraryItem>> = libraryObserver
        .observeInProgressItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completed: StateFlow<List<LibraryItem>> = libraryObserver
        .observeFinishedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isEmpty: StateFlow<Boolean> = combine(inProgress, completed) { ip, c ->
        ip.isEmpty() && c.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    companion object {
        internal const val COMPLETED_THRESHOLD = 0.9f
    }
}
