package com.riffle.app.feature.source.websource

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.BookGrid
import com.riffle.feature.designsystem.BookSectionGrid
import com.riffle.feature.designsystem.LocalCoverGridScale
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.pinchCoverZoom
import com.riffle.feature.library.LibrarySectionType
import com.riffle.core.data.ToReadRepository
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.core.data.websource.PositionTombstoneWriter
import com.riffle.core.data.websource.RemoteItemFreshness
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    private val positionTombstoneWriter: PositionTombstoneWriter,
    private val remoteItemFreshness: RemoteItemFreshness,
    connectivityObserver: ConnectivityObserver,
    private val offlineAvailability: LibraryItemOfflineAvailability,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    val isOffline: StateFlow<Boolean> = connectivityObserver.isOnline
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val inProgress: StateFlow<List<LibraryItem>> =
        combine(libraryObserver.observeInProgressItems(libraryId), isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val finished: StateFlow<List<LibraryItem>> =
        combine(libraryObserver.observeFinishedItems(libraryId), isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueSeries: StateFlow<List<LibraryItem>> =
        combine(libraryObserver.observeContinueSeriesItems(libraryId), isOffline) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toReadItems: StateFlow<List<LibraryItem>> =
        combine(
            webSourceToReadItems(
                toReadItemIds = toReadRepository.observeToReadItemIds(libraryId),
                allBooks = libraryObserver.observeAllBooks(libraryId),
            ),
            isOffline,
        ) { items, offline ->
            if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeFromLibrary(sourceId: String, itemId: String) {
        viewModelScope.launch {
            removeFromLibrary(
                sourceId = sourceId,
                itemId = itemId,
                libraryMutator = libraryMutator,
                hideItem = positionTombstoneWriter::markDeleted,
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
    hideItem: suspend (String, String) -> Unit,
) {
    // Tombstone the position rows before deleting the library row so a concurrent sweep
    // cannot observe the library row as gone while the position row is still live (and
    // re-insert the item back into the library grid).
    hideItem(sourceId, itemId)
    libraryMutator.deleteItem(sourceId, itemId)
}

@Composable
fun WebSourceHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    viewModel: WebSourceLibraryViewModel = koinViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val scale = LocalCoverGridScale.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pinchCoverZoom(scale, onCoverScaleChange),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ui_section_in_progress)) }
            item { BookSectionGrid(items = inProgress, token = "", onItemSelected = { onOpenDetail(it.id) }, onItemLongPress = { viewModel.removeFromLibrary(it.sourceId, it.id) }) }
        }
        if (continueSeries.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ui_section_continue_series)) }
            item { BookSectionGrid(items = continueSeries, token = "", onItemSelected = { onOpenDetail(it.id) }, onItemLongPress = { viewModel.removeFromLibrary(it.sourceId, it.id) }) }
        }
        if (finished.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ui_section_completed)) }
            item { BookSectionGrid(items = finished, token = "", onItemSelected = { onOpenDetail(it.id) }, onItemLongPress = { viewModel.removeFromLibrary(it.sourceId, it.id) }) }
        }
    }
}

@Composable
fun WebSourceToReadTab(
    onOpenDetail: (itemId: String) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    viewModel: WebSourceLibraryViewModel = koinViewModel(),
) {
    val items by viewModel.toReadItems.collectAsState()
    val scale = LocalCoverGridScale.current
    BookGrid(
        items = items,
        token = "",
        onItemSelected = { onOpenDetail(it.id) },
        modifier = Modifier.fillMaxSize().pinchCoverZoom(scale, onCoverScaleChange),
    )
}
