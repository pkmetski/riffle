package com.riffle.feature.source.ui.websource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ReadingHistoryCapability
import com.riffle.core.catalog.oreilly.oReillyFriendlyErrorMessage
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the O'Reilly Home tab's "Continue Reading" and "Completed" sections by fetching
 * reading history directly from `learning.oreilly.com`.
 *
 * Items with server-reported progress below [COMPLETED_THRESHOLD] go into [inProgress];
 * items at or above it go into [completed]. Items the server returns with no progress
 * are excluded by the parser.
 */
class OReillyHomeViewModel(
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemUpserter: WebSourceLibraryItemUpserter,
    private val webSourceItemGate: WebSourceItemGate,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    data class OpenDetailEvent(val itemId: String)

    private val _openDetailEvents = MutableSharedFlow<OpenDetailEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openDetailEvents: SharedFlow<OpenDetailEvent> = _openDetailEvents.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _allHistory = MutableStateFlow<List<CatalogItem>>(emptyList())

    val inProgress: StateFlow<List<CatalogItem>> = _allHistory
        .map { items -> items.filter { (it.readingProgress ?: 0f) in 0.01f..<COMPLETED_THRESHOLD } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completed: StateFlow<List<CatalogItem>> = _allHistory
        .map { items -> items.filter { (it.readingProgress ?: 0f) >= COMPLETED_THRESHOLD } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isEmpty: StateFlow<Boolean> = combine(isLoading, inProgress, completed) { loading, ip, c ->
        !loading && ip.isEmpty() && c.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            val source = sourceRepository.getActive()?.takeIf { it.type == SourceType.OREILLY }
                ?: return@launch
            val catalog = catalogRegistry.forSource(source)
            if (catalog !is ReadingHistoryCapability) return@launch
            _isLoading.value = true
            _error.value = null
            try {
                _allHistory.value = catalog.fetchReadingHistory()
            } catch (t: Throwable) {
                _error.value = oReillyFriendlyErrorMessage(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openDetail(item: CatalogItem) {
        viewModelScope.launch {
            val source = sourceRepository.getActive()?.takeIf { it.type == SourceType.OREILLY }
                ?: return@launch
            val catalog = catalogRegistry.forSource(source)
            if (catalog != null) {
                webSourceItemGate.openItem(sourceId = source.id, listing = item, catalog = catalog)
            } else {
                libraryItemUpserter.upsert(source.id, item)
            }
            _openDetailEvents.emit(OpenDetailEvent(itemId = item.id))
        }
    }

    companion object {
        internal const val COMPLETED_THRESHOLD = 0.9f
    }
}
