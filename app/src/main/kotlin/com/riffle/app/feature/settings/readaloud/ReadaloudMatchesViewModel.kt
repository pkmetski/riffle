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
import com.riffle.core.domain.usecase.ReadaloudReviewActions
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadaloudMatchesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val reviewRepository: ReadaloudReviewRepository,
    private val reviewActions: ReadaloudReviewActions,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val sourceId: String = savedStateHandle.get<String>("sourceId") ?: ""

    /** Set when the screen was opened from a readaloud's "Pair manually" footer link. */
    val pairBookId: String? = savedStateHandle.get<String>("pairBookId")?.takeIf { it.isNotEmpty() }

    /**
     * The ABS Source the user is currently matching against — the one whose library is shown
     * in the manual picker and whose candidate suggestions appear in "Suggested". Picked as:
     * the active ABS Source, else any other ABS Source, else empty. Empty disables the picker
     * (no results) so the picker can't link a Storyteller readaloud against the wrong account
     * just because no ABS server is configured. See ADR 0021 and the pkmetski/readaloud-
     * manual-match-crash PR — multiple ABS accounts pointing at the same library would
     * otherwise produce indistinguishable duplicate rows.
     */
    val activeAbsServerId: StateFlow<String> = sourceRepository.observeAll()
        .map { servers ->
            // Filter by SourceType.ABS too — LocalFiles rows carry
            // `serverType = AUDIOBOOKSHELF` as a placeholder and would otherwise be picked as
            // "active ABS", pointing readaloud matching at a source with no ABS API.
            val abs = servers.filter {
                it.type == com.riffle.core.models.SourceType.ABS &&
                    it.serverType == ServerType.AUDIOBOOKSHELF
            }
            (abs.firstOrNull { it.isActive } ?: abs.firstOrNull())?.id ?: ""
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val review: StateFlow<ReadaloudReview> = activeAbsServerId
        .flatMapLatest { absId -> reviewRepository.observeReview(sourceId, absId.takeIf { it.isNotEmpty() }) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ReadaloudReview(emptyList(), emptyList(), emptyList()),
        )

    /** Per-server auth tokens so cover thumbnails across every ABS Source render. */
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
            sourceRepository.observeAll().first().forEach { server ->
                tokenStorage.getToken(server.id)?.let { map[server.id] = it }
            }
            _tokensByServer.value = map
        }
        viewModelScope.launch {
            combine(_pickerQuery.debounce(200), _pickerFilter, activeAbsServerId) { q, f, absId -> Triple(q, f, absId) }
                .collect { (query, filter, absId) ->
                    _pickerResults.value = reviewRepository.searchAbsItems(absId, query, filter)
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
            reviewActions.confirmCandidate(
                book.storytellerSourceId, book.storytellerBookId,
                candidate.absSourceId, candidate.absLibraryItemId,
            )
        }
    }

    fun dismissCandidate(book: PendingReadaloud, candidate: AbsCandidate) {
        viewModelScope.launch {
            reviewActions.dismissCandidate(
                book.storytellerSourceId, book.storytellerBookId,
                candidate.absSourceId, candidate.absLibraryItemId,
            )
        }
    }

    fun dismissBook(book: PendingReadaloud) {
        viewModelScope.launch {
            reviewActions.dismissBook(book.storytellerSourceId, book.storytellerBookId)
        }
    }

    fun unlinkBook(link: ConfirmedReadaloud) {
        viewModelScope.launch {
            reviewActions.unlinkBook(link.storytellerSourceId, link.storytellerBookId)
        }
    }

    /** Detach a single ABS item from a readaloud (used by the picker's per-row Unlink). */
    fun unlinkAbsItem(item: AbsPickerItem) {
        viewModelScope.launch {
            reviewActions.unlinkAbsItem(item.absSourceId, item.absLibraryItemId)
        }
    }

    fun onPickerQueryChange(query: String) {
        _pickerQuery.value = query
    }

    fun pairManually(storytellerBookId: String, item: AbsPickerItem) {
        viewModelScope.launch {
            reviewActions.pairManually(
                sourceId, storytellerBookId, item.absSourceId, item.absLibraryItemId,
            )
        }
    }
}
