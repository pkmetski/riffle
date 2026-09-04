package com.riffle.app.feature.source.websource

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.library.HomeTabContent
import com.riffle.feature.library.LibrarySectionType
import com.riffle.app.feature.library.ToReadTabContent
import com.riffle.core.data.ToReadRepository
import com.riffle.core.data.websource.RemoteItemFreshness
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Room-backed acquired-item shelves shared by every Web Source.
 *
 * Web Sources own different remote browse surfaces, but once an item has been opened/upserted it
 * is represented by the same local `library_items` rows. This ViewModel keeps Home and To Read
 * shelf plumbing out of each concrete Source screen.
 */
class WebSourceLibraryViewModel constructor(
    savedStateHandle: SavedStateHandle,
    libraryObserver: LibraryObserver,
    toReadRepository: ToReadRepository,
    private val libraryMutator: LibraryMutator,
    private val remoteItemFreshness: RemoteItemFreshness,
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

    val toReadItems: StateFlow<List<LibraryItem>> = webSourceToReadItems(
        toReadItemIds = toReadRepository.observeToReadItemIds(libraryId),
        allBooks = libraryObserver.observeAllBooks(libraryId),
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeFromLibrary(sourceId: String, itemId: String) {
        viewModelScope.launch {
            removeFromLibrary(
                sourceId = sourceId,
                itemId = itemId,
                libraryMutator = libraryMutator,
                clearFreshness = remoteItemFreshness::clear,
            )
        }
    }
}

internal fun webSourceToReadItems(
    toReadItemIds: Flow<Set<String>>,
    allBooks: Flow<List<LibraryItem>>,
): Flow<List<LibraryItem>> = combine(toReadItemIds, allBooks) { ids, all ->
    all.filter { it.id in ids }
}

internal suspend fun removeFromLibrary(
    sourceId: String,
    itemId: String,
    libraryMutator: LibraryMutator,
    clearFreshness: suspend (String, String) -> Unit,
) {
    libraryMutator.deleteItem(sourceId, itemId)
    clearFreshness(sourceId, itemId)
}

@Composable
fun WebSourceHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    viewModel: WebSourceLibraryViewModel = koinViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()

    HomeTabContent(
        inProgress = inProgress,
        continueSeries = continueSeries,
        recentlyAdded = recentlyAdded,
        finished = finished,
        isLoading = false,
        token = "",
        onItemSelected = { item -> onOpenDetail(item.id) },
        onSectionSeeMore = onSectionSeeMore,
        onCoverScaleChange = onCoverScaleChange,
        onItemLongPress = { item -> viewModel.removeFromLibrary(item.sourceId, item.id) },
    )
}

@Composable
fun WebSourceToReadTab(
    onOpenDetail: (itemId: String) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    viewModel: WebSourceLibraryViewModel = koinViewModel(),
) {
    val items by viewModel.toReadItems.collectAsState()
    ToReadTabContent(
        items = items,
        isLoading = false,
        token = "",
        onItemSelected = { item -> onOpenDetail(item.id) },
        onCoverScaleChange = onCoverScaleChange,
    )
}
