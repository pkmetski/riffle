package com.riffle.app.feature.source.websource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
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

/**
 * Shared ViewModel base for the browse screens of every unbounded web source (Chitanka,
 * Gutenberg, and every future `SourceType.isUnboundedCatalog == true` source). Wraps the
 * facet/query/pagination state machine that both `ChitankaBrowseViewModel` and
 * `GutenbergBrowseViewModel` shared before ADR 0044 Phase 5.
 *
 * Adding a new unbounded source is a ~10-line subclass:
 *
 * ```kotlin
 * @HiltViewModel
 * class FooBrowseViewModel @Inject constructor(
 *     savedStateHandle: SavedStateHandle,
 *     sourceRepository: SourceRepository,
 *     catalogRegistry: CatalogRegistry,
 *     libraryItemUpserter: WebSourceLibraryItemUpserter,
 *     webSourceItemGate: WebSourceItemGate,
 * ) : UnboundedBrowseViewModel(
 *     savedStateHandle, sourceRepository, catalogRegistry, libraryItemUpserter, webSourceItemGate,
 *     sourceType = SourceType.FOO,
 *     defaultRootId = FooCatalog.ROOT_BOOKS,
 *     pageSize = 40,
 *     friendlyError = ::fooFriendlyErrorMessage,
 * )
 * ```
 *
 * The subclass carries only its `SourceType` guard and its tuning: the `defaultRootId` used when
 * SavedStateHandle has no `libraryId`, the network page size, and the source-specific
 * error-copy mapper (`"Couldn't reach chitanka.info"` vs `"Couldn't reach gutendex.com"`).
 */
abstract class UnboundedBrowseViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemUpserter: WebSourceLibraryItemUpserter,
    private val webSourceItemGate: WebSourceItemGate,
    private val sourceType: SourceType,
    defaultRootId: String,
    private val pageSize: Int,
    private val friendlyError: (Throwable) -> String,
) : ViewModel() {

    // The unbounded browse route uses the Riffle `libraryId` as the Catalog `rootId` — each of
    // the source's Riffle "libraries" (e.g. Chitanka's Books + Audiobooks) maps 1:1 to a Catalog
    // root. Reading the route arg as `libraryId` keeps this SavedStateHandle compatible with
    // AnnotationsListViewModel (which looks for a `libraryId` key) when we embed it in the
    // source screen's Annotations tab.
    val rootId: String = savedStateHandle.get<String>("libraryId") ?: defaultRootId

    /**
     * Emitted once the tapped [CatalogItem] has been upserted into `library_items` and the
     * standard item-detail screen can safely resolve it via `LibraryObserver.getItem`. The
     * screen collects this and routes to `library_item_detail/{id}` (see MainScreen).
     */
    data class OpenDetailEvent(val itemId: String)

    private val _openDetailEvents = MutableSharedFlow<OpenDetailEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openDetailEvents: SharedFlow<OpenDetailEvent> = _openDetailEvents.asSharedFlow()

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

    /**
     * True while a next-page fetch is in flight. Distinct from [isLoading] (whole-list refresh)
     * so the UI can show a small footer spinner without pulling the whole grid into a loading
     * state.
     */
    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging.asStateFlow()

    /**
     * False once a page came back with fewer items than [pageSize] — the catalogue is exhausted
     * for the current (facet, query, rootId) tuple. The grid stops calling [loadMore] to avoid
     * hammering the server with empty pages.
     */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentPage = 0
    private var searchDebounceJob: Job? = null
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch { loadFacets() }
        refresh()
    }

    private suspend fun activeCatalog() =
        sourceRepository.getActive()
            ?.takeIf { it.type == sourceType }
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
        loadMoreJob?.cancel()
        // Reset pagination cursor on refresh so the next loadMore starts from page 1 rather
        // than whatever the previous (facet, query) tuple was at.
        currentPage = 0
        _hasMore.value = true
        refreshJob = viewModelScope.launch { refreshOnce() }
    }

    /**
     * Fetch the next page and append. No-op when a whole-list refresh is still in flight, when
     * a previous loadMore hasn't finished yet, or when [hasMore] has already been flipped to
     * false (last page returned short). The grid drives this on end-of-list scroll.
     */
    fun loadMore() {
        if (refreshJob?.isActive == true) return
        if (loadMoreJob?.isActive == true) return
        if (!_hasMore.value) return
        if (_items.value.isEmpty()) return  // nothing to append to — a real refresh handles page 1
        loadMoreJob = viewModelScope.launch { loadMoreOnce() }
    }

    /**
     * Open the tapped item's detail. Routes through [WebSourceItemGate] which enforces the
     * ADR-0043 caching policy. See ChitankaBrowseViewModel's inline comment on `openDetail` for
     * the state-machine notes — same behaviour for every unbounded source.
     */
    fun openDetail(item: CatalogItem) {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
                ?.takeIf { it.type == sourceType }
                ?: return@launch
            val catalog = activeCatalog()
            if (catalog != null) {
                webSourceItemGate.openItem(
                    sourceId = source.id,
                    listing = item,
                    catalog = catalog,
                )
            } else {
                // CatalogRegistry couldn't build a catalog for the active source — should never
                // happen in practice, but if it does, at least keep tap → detail navigable.
                libraryItemUpserter.upsert(source.id, item)
            }
            _openDetailEvents.emit(OpenDetailEvent(itemId = item.id))
        }
    }

    private suspend fun refreshOnce() {
        val catalog = activeCatalog() ?: return
        _isLoading.value = true
        _error.value = null
        try {
            val q = _query.value.trim()
            val result = if (q.isNotEmpty()) {
                catalog.search(rootId = rootId, query = q, page = 0, pageSize = pageSize)
            } else {
                val facet = _selectedFacet.value?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = 0, pageSize = pageSize, facet = facet)
            }
            _items.value = result
            currentPage = 0
            // A short first page IS the last page — nothing left to fetch. Flip hasMore so the
            // grid stops calling loadMore.
            _hasMore.value = result.size >= pageSize
        } catch (t: Throwable) {
            _error.value = friendlyError(t)
            _items.value = emptyList()
            _hasMore.value = false
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun loadMoreOnce() {
        val catalog = activeCatalog() ?: return
        _isPaging.value = true
        try {
            val nextPage = currentPage + 1
            val q = _query.value.trim()
            val next = if (q.isNotEmpty()) {
                catalog.search(rootId = rootId, query = q, page = nextPage, pageSize = pageSize)
            } else {
                val facet = _selectedFacet.value?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = nextPage, pageSize = pageSize, facet = facet)
            }
            if (next.isEmpty()) {
                _hasMore.value = false
                return
            }
            // De-dup on id in case the catalogue returned overlapping pages (some sources'
            // paged views occasionally repeat the tail of the previous page).
            val existingIds = _items.value.mapTo(HashSet()) { it.id }
            val appended = next.filter { it.id !in existingIds }
            if (appended.isNotEmpty()) {
                _items.value = _items.value + appended
            }
            currentPage = nextPage
            _hasMore.value = next.size >= pageSize
        } catch (_: Throwable) {
            // A pagination failure isn't fatal — the user still sees the pages already loaded.
            // Leave hasMore true so a subsequent scroll retries the same page.
        } finally {
            _isPaging.value = false
        }
    }
}
