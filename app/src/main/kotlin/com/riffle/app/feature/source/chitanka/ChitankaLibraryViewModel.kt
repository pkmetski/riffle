package com.riffle.app.feature.source.chitanka

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
 * Room-backed shelves for the Chitanka library "Home" tab. Feeds the same
 * `HomeTabContent` composable as [com.riffle.app.feature.library.LibraryItemsScreen], but
 * without the ABS-shaped refresh loop — Chitanka has no server-side library mirror to
 * refresh (ADR 0041/0042), so this VM only observes rows the user has upserted via
 * [com.riffle.core.data.chitanka.ChitankaLibraryItemUpserter] on tap.
 */
@HiltViewModel
class ChitankaLibraryViewModel @Inject constructor(
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

    /**
     * Joins the To Read id-set from [ToReadRepository] (which for Chitanka falls through to the
     * local Preferences store — see [com.riffle.core.data.LocalToReadStore]) with the Room-backed
     * catalogue rows. Only items that have already been upserted into `library_items` via
     * [com.riffle.core.data.chitanka.ChitankaLibraryItemUpserter] can appear here — an id that's
     * been added to To Read but whose row was later deleted just drops out silently.
     */
    val toReadItems: StateFlow<List<LibraryItem>> = combine(
        toReadRepository.observeToReadItemIds(libraryId),
        libraryObserver.observeAllBooks(libraryId),
    ) { ids, all -> all.filter { it.id in ids } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
