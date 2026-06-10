package com.riffle.app.feature.settings.readaloud

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AbsCandidate
import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class ReadaloudMatchesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val reviewRepository: ReadaloudReviewRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

    /** Set when the screen was opened from a readaloud's "Pair manually" footer link. */
    val pairBookId: String? = savedStateHandle.get<String>("pairBookId")?.takeIf { it.isNotEmpty() }

    val review: StateFlow<ReadaloudReview> = reviewRepository.observeReview(serverId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ReadaloudReview(emptyList(), emptyList(), emptyList()),
        )

    /** Per-server auth tokens so cover thumbnails across every ABS Server render. */
    private val _tokensByServer = MutableStateFlow<Map<String, String>>(emptyMap())
    val tokensByServer: StateFlow<Map<String, String>> = _tokensByServer.asStateFlow()

    private val _pickerQuery = MutableStateFlow("")
    val pickerQuery: StateFlow<String> = _pickerQuery.asStateFlow()

    // Scopes the picker to the slot it was opened for: EBOOK / AUDIO from a Confirmed match's empty
    // slot, ANY from an Unmatched row's "Match manually…".
    private val _pickerFilter = MutableStateFlow(AbsFormatFilter.ANY)
    val pickerFilter: StateFlow<AbsFormatFilter> = _pickerFilter.asStateFlow()

    private val _pickerResults = MutableStateFlow<List<AbsPickerItem>>(emptyList())
    val pickerResults: StateFlow<List<AbsPickerItem>> = _pickerResults.asStateFlow()

    init {
        viewModelScope.launch {
            // One snapshot is enough; tokens don't change while this screen is open.
            val map = mutableMapOf<String, String>()
            serverRepository.observeAll().first().forEach { server ->
                tokenStorage.getToken(server.id)?.let { map[server.id] = it }
            }
            _tokensByServer.value = map
        }
        viewModelScope.launch {
            combine(_pickerQuery.debounce(200), _pickerFilter) { query, filter -> query to filter }
                .collect { (query, filter) ->
                    _pickerResults.value = reviewRepository.searchAbsItems(query, filter)
                }
        }
    }

    /**
     * Opens the picker scoped to [filter] (called from a Confirmed match's empty slot or an
     * Unmatched row). Resets the query so stale results don't flash with a different filter.
     */
    fun setPickerFilter(filter: AbsFormatFilter) {
        _pickerQuery.value = ""
        _pickerFilter.value = filter
    }

    /** Clears picker state when the dialog is dismissed so the next open starts unfiltered. */
    fun closePicker() {
        _pickerQuery.value = ""
        _pickerFilter.value = AbsFormatFilter.ANY
    }

    fun confirm(book: PendingReadaloud, candidate: AbsCandidate) {
        viewModelScope.launch {
            reviewRepository.confirmCandidate(
                book.storytellerServerId, book.storytellerBookId,
                candidate.absServerId, candidate.absLibraryItemId,
            )
        }
    }

    fun dismissCandidate(book: PendingReadaloud, candidate: AbsCandidate) {
        viewModelScope.launch {
            reviewRepository.dismissCandidate(
                book.storytellerServerId, book.storytellerBookId,
                candidate.absServerId, candidate.absLibraryItemId,
            )
        }
    }

    fun dismissBook(book: PendingReadaloud) {
        viewModelScope.launch {
            reviewRepository.dismissBook(book.storytellerServerId, book.storytellerBookId)
        }
    }

    fun unlinkBook(link: ConfirmedReadaloud) {
        viewModelScope.launch {
            reviewRepository.unlinkBook(link.storytellerServerId, link.storytellerBookId)
        }
    }

    /** Detach a single ABS item from a readaloud (used by the picker's per-row Unlink). */
    fun unlinkAbsItem(item: AbsPickerItem) {
        viewModelScope.launch {
            reviewRepository.unlinkAbsItem(item.absServerId, item.absLibraryItemId)
        }
    }

    fun onPickerQueryChange(query: String) {
        _pickerQuery.value = query
    }

    fun pairManually(storytellerBookId: String, item: AbsPickerItem) {
        viewModelScope.launch {
            reviewRepository.pairManually(
                serverId, storytellerBookId, item.absServerId, item.absLibraryItemId,
            )
        }
    }
}
