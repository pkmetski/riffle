package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Room-backed shelves for the Gutenberg library "Home" tab. Feeds the same `HomeTabContent`
 * composable as ABS libraries do, but without the ABS-shaped refresh loop — Gutenberg has no
 * server-side library mirror to refresh, so this VM only observes rows the user has upserted
 * via [com.riffle.core.data.websource.WebSourceLibraryItemUpserter] on tap.
 */
@HiltViewModel
class GutenbergLibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryObserver: LibraryObserver,
    toReadRepository: ToReadRepository,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    val inProgress: StateFlow<List<LibraryItem>> = libraryObserver.observeInProgressItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAdded: StateFlow<List<LibraryItem>> = libraryObserver.observeRecentlyAddedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val finished: StateFlow<List<LibraryItem>> = libraryObserver.observeFinishedItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueSeries: StateFlow<List<LibraryItem>> = libraryObserver.observeContinueSeriesItems(libraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toReadItems: StateFlow<List<LibraryItem>> = combine(
        toReadRepository.observeToReadItemIds(libraryId),
        libraryObserver.observeAllBooks(libraryId),
    ) { ids, all -> all.filter { it.id in ids } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
