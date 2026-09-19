package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.feature.library.LibraryTabVisibility
import com.riffle.feature.library.LibraryTabVisibilityObserver
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Thin Hilt-scoped wrapper that reads `libraryId` from `SavedStateHandle` and exposes the
 * observer's flow as a lifecycle-bound `StateFlow`. Any screen — server-source or web-source —
 * can wire tab visibility in one line:
 *
 * ```
 * val visibility by koinViewModel<LibraryTabVisibilityViewModel>().visibility.collectAsState()
 * ```
 */
class LibraryTabVisibilityViewModel constructor(
    savedStateHandle: SavedStateHandle,
    observer: LibraryTabVisibilityObserver,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    val visibility: StateFlow<LibraryTabVisibility> = observer.observe(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryTabVisibility.Empty)
}
