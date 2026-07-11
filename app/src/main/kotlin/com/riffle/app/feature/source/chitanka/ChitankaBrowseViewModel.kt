package com.riffle.app.feature.source.chitanka

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.data.chitanka.ChitankaLibraryItemUpserter
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [ChitankaBrowseScreen]. Talks directly to the ChitankaCatalog instance built
 * by [CatalogRegistry] for the active Chitanka Source row — there is no Room-backed
 * `library_items` mirror for this Source (unbounded catalogue per ADR 0041/0042), so browse
 * and search are executed on demand.
 *
 * Facet state is remembered in [SavedStateHandle] so process death round-trips cleanly. Search
 * debounces user input to keep the network well-behaved.
 */
@HiltViewModel
class ChitankaBrowseViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemUpserter: ChitankaLibraryItemUpserter,
) : ViewModel() {

    val rootId: String = savedStateHandle.get<String>("rootId") ?: ChitankaCatalog.ROOT_BOOKS

    /**
     * Emitted once the tapped [CatalogItem] has been upserted into `library_items` and the
     * reader / audiobook player can safely be launched for it. The screen collects this and
     * routes to `epub_reader/{id}` or `audiobook_player/{id}` (see MainScreen).
     *
     * Extra-buffer replay-0 so a nav that fires before the collector attaches (should never
     * happen — the screen collects in composition) is dropped rather than replayed on the
     * next screen return.
     */
    data class OpenEvent(val itemId: String, val isAudio: Boolean)

    private val _openEvents = MutableSharedFlow<OpenEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openEvents: SharedFlow<OpenEvent> = _openEvents.asSharedFlow()

    private val _facets = MutableStateFlow<List<CatalogFacet>>(emptyList())
    val facets: StateFlow<List<CatalogFacet>> = _facets.asStateFlow()

    private val _selectedFacet = MutableStateFlow<String?>(savedStateHandle["facetKey"])
    val selectedFacet: StateFlow<String?> = _selectedFacet.asStateFlow()

    private val _items = MutableStateFlow<List<CatalogItem>>(emptyList())
    val items: StateFlow<List<CatalogItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private var searchDebounceJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { loadFacets() }
        refresh()
    }

    private suspend fun activeCatalog() =
        sourceRepository.getActive()
            ?.takeIf { it.type == SourceType.CHITANKA }
            ?.let { catalogRegistry.forSource(it) }

    private suspend fun loadFacets() {
        val catalog = activeCatalog() ?: return
        _facets.value = runCatching { catalog.listFacets(rootId) }.getOrElse { emptyList() }
    }

    fun selectFacet(key: String?) {
        _selectedFacet.value = key
        savedStateHandle["facetKey"] = key
        viewModelScope.launch { refresh() }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun refresh() {
        // Cancel any in-flight refresh before starting a new one. Without this a slow response
        // from a previous facet/query can arrive after a newer one and overwrite _items with
        // stale results (chip strip shows B, grid shows A's late-arriving items).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshOnce() }
    }

    /**
     * Upsert the tapped item into `library_items` so the reader/player can resolve it via
     * `LibraryObserver.getItem`, then emit an [OpenEvent] the screen collects to navigate.
     * No-op when there is no active Chitanka Source — the screen route wouldn't have been
     * reachable, but guard defensively.
     */
    fun openItem(item: CatalogItem) {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
                ?.takeIf { it.type == SourceType.CHITANKA }
                ?: return@launch
            libraryItemUpserter.upsert(source.id, item)
            _openEvents.emit(
                OpenEvent(itemId = item.id, isAudio = rootId == ChitankaCatalog.ROOT_AUDIOBOOKS),
            )
        }
    }

    private suspend fun refreshOnce() {
        val catalog = activeCatalog() ?: return
        _isLoading.value = true
        _error.value = null
        try {
            val q = _query.value.trim()
            val result = if (q.isNotEmpty()) {
                catalog.search(rootId = rootId, query = q, page = 0, pageSize = 50)
            } else {
                val facet = _selectedFacet.value?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = 0, pageSize = 50, facet = facet)
            }
            _items.value = result
        } catch (t: Throwable) {
            _error.value = t.message ?: t::class.simpleName ?: "Error"
            _items.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }
}
