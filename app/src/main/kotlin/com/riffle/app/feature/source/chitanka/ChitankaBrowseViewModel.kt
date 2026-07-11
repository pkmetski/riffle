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
import com.riffle.core.data.websource.WebSourceItemGate
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
import java.io.IOException
import java.net.UnknownHostException
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
    private val webSourceItemGate: WebSourceItemGate,
) : ViewModel() {

    // Chitanka's browse route uses the Riffle `libraryId` as the Catalog `rootId` — the two
    // Chitanka "libraries" (Books and Audiobooks) each map 1:1 to a Catalog root. Reading the
    // route arg as `libraryId` keeps this SavedStateHandle compatible with
    // AnnotationsListViewModel (which looks for a `libraryId` key) when we embed it in the
    // Chitanka screen's Annotations tab.
    val rootId: String = savedStateHandle.get<String>("libraryId") ?: ChitankaCatalog.ROOT_BOOKS

    /**
     * Emitted once the tapped [CatalogItem] has been upserted into `library_items` and the
     * standard item detail screen can safely resolve it via `LibraryObserver.getItem`. The
     * screen collects this and routes to `library_item_detail/{id}` (see MainScreen).
     *
     * Extra-buffer replay-0 so a nav that fires before the collector attaches (should never
     * happen — the screen collects in composition) is dropped rather than replayed on the
     * next screen return.
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
     * True while a next-page fetch is in flight. Distinct from [isLoading] (whole-list refresh) so
     * the UI can show a small footer spinner without pulling the whole grid into a loading state.
     */
    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging.asStateFlow()

    /**
     * False once a page came back with fewer items than [PAGE_SIZE] — the catalogue is exhausted
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
        loadMoreJob?.cancel()
        // Reset pagination cursor on refresh so the next loadMore starts from page 1 rather than
        // whatever the previous (facet, query) tuple was at.
        currentPage = 0
        _hasMore.value = true
        refreshJob = viewModelScope.launch { refreshOnce() }
    }

    /**
     * Fetch the next page and append. No-op when a whole-list refresh is still in flight, when a
     * previous loadMore hasn't finished yet, or when [hasMore] has already been flipped to false
     * (last page returned short). The grid drives this on end-of-list scroll.
     */
    fun loadMore() {
        if (refreshJob?.isActive == true) return
        if (loadMoreJob?.isActive == true) return
        if (!_hasMore.value) return
        if (_items.value.isEmpty()) return  // nothing to append to — a real refresh handles the first page
        loadMoreJob = viewModelScope.launch { loadMoreOnce() }
    }

    /**
     * Open the tapped item's detail. Routes through [WebSourceItemGate] which enforces the
     * ADR-0043 caching policy: within TTL, no network call — the existing `library_items` row
     * is served; expired but reachable, refetch + upsert + stamp; expired but offline, serve
     * the persisted (possibly stale) row anyway. If the gate reports [WebSourceItemGate.Outcome.Failed]
     * (no persisted row and no network), fall back to upserting the listing [CatalogItem] as-is
     * so navigation still happens — the detail screen renders with the fields the search-results
     * HTML exposed (description/series/year/genres null).
     *
     * No-op when there is no active Chitanka Source — the screen route wouldn't have been
     * reachable, but guard defensively.
     */
    fun openDetail(item: CatalogItem) {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
                ?.takeIf { it.type == SourceType.CHITANKA }
                ?: return@launch
            val catalog = activeCatalog()
            if (catalog != null) {
                val outcome = webSourceItemGate.openItem(
                    sourceId = source.id,
                    itemId = item.id,
                    catalog = catalog,
                    upsert = { libraryItemUpserter.upsert(source.id, it) },
                )
                if (outcome is WebSourceItemGate.Outcome.Failed) {
                    libraryItemUpserter.upsert(source.id, item)
                }
            } else {
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
                catalog.search(rootId = rootId, query = q, page = 0, pageSize = PAGE_SIZE)
            } else {
                val facet = _selectedFacet.value?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = 0, pageSize = PAGE_SIZE, facet = facet)
            }
            _items.value = result
            currentPage = 0
            // A short first page IS the last page — nothing left to fetch. Flip hasMore so the
            // grid stops calling loadMore.
            _hasMore.value = result.size >= PAGE_SIZE
        } catch (t: Throwable) {
            _error.value = friendlyErrorMessage(t)
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
                catalog.search(rootId = rootId, query = q, page = nextPage, pageSize = PAGE_SIZE)
            } else {
                val facet = _selectedFacet.value?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = nextPage, pageSize = PAGE_SIZE, facet = facet)
            }
            if (next.isEmpty()) {
                _hasMore.value = false
                return
            }
            // De-dup on id in case the catalogue returned overlapping pages (Chitanka's paged views
            // occasionally repeat the tail of the previous page).
            val existingIds = _items.value.mapTo(HashSet()) { it.id }
            val appended = next.filter { it.id !in existingIds }
            if (appended.isNotEmpty()) {
                _items.value = _items.value + appended
            }
            currentPage = nextPage
            _hasMore.value = next.size >= PAGE_SIZE
        } catch (_: Throwable) {
            // A pagination failure isn't fatal — the user still sees the pages already loaded.
            // Leave hasMore true so a subsequent scroll retries the same page.
        } finally {
            _isPaging.value = false
        }
    }

    private companion object {
        // Matches the initial `page=0` request. Chitanka lists ~30 items per page in most views;
        // 50 gives us a small safety margin so the grid usually has to scroll before we page again.
        const val PAGE_SIZE = 50
    }
}

/**
 * Map network failures to messages users can act on. The raw OkHttp/DNS text
 * (`Unable to resolve host "chitanka.info": No address associated with hostname`)
 * leaks implementation and reads like a crash; offline is the by-far common cause.
 */
internal fun friendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach chitanka.info. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
