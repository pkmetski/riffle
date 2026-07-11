package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogFacet
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.data.gutenberg.GutenbergLibraryItemUpserter
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
 * ViewModel for [GutenbergBrowseScreen]. Talks directly to the GutenbergCatalog instance built
 * by [CatalogRegistry] for the active Gutenberg Source row — there is no Room-backed
 * `library_items` mirror for this Source (unbounded catalogue), so browse and search are
 * executed on demand.
 *
 * Mirrors the shape of [com.riffle.app.feature.source.chitanka.ChitankaBrowseViewModel]. Kept as
 * a distinct type (rather than a generic "UnboundedCatalog" VM) so each Source can tune its
 * pagination + error copy independently — Gutendex serves ~32 items per page vs Chitanka's ~50.
 */
@HiltViewModel
class GutenbergBrowseViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemUpserter: GutenbergLibraryItemUpserter,
    private val logger: Logger,
) : ViewModel() {

    val rootId: String = savedStateHandle.get<String>("libraryId") ?: GutenbergCatalog.ROOT_BOOKS

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

    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging.asStateFlow()

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
            ?.takeIf { it.type == SourceType.GUTENBERG }
            ?.let { catalogRegistry.forSource(it) }

    private suspend fun loadFacets() {
        val catalog = activeCatalog() ?: return
        _facets.value = runCatching { catalog.listFacets(rootId) }.getOrElse { emptyList() }
    }

    private var facetDebounceJob: Job? = null

    fun selectFacet(key: String?) {
        // Reflect the selection in the chip strip immediately so the UI feels responsive, but
        // debounce the actual network fetch. Rapid tapping across facets (Fiction → History →
        // Poetry in <1 s) would otherwise fire 3 back-to-back Gutendex requests, exhaust the
        // HTTP/1.1 5-per-host connection budget, and stall the final one behind two doomed
        // in-flight ones — user-visible as "the third tap infinite-loads." Refresh() cancels
        // any in-flight fetch, but Cloudflare doesn't release the socket instantly.
        _selectedFacet.value = key
        savedStateHandle["facetKey"] = key
        facetDebounceJob?.cancel()
        facetDebounceJob = viewModelScope.launch {
            delay(FACET_DEBOUNCE_MS)
            refresh()
        }
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
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        currentPage = 0
        _hasMore.value = true
        refreshJob = viewModelScope.launch { refreshOnce() }
    }

    fun loadMore() {
        if (refreshJob?.isActive == true) return
        if (loadMoreJob?.isActive == true) return
        if (!_hasMore.value) return
        if (_items.value.isEmpty()) return
        loadMoreJob = viewModelScope.launch { loadMoreOnce() }
    }

    /**
     * Upsert the tapped item into `library_items` so the standard detail screen can resolve it,
     * then emit an [OpenDetailEvent]. Falls back to the listing item if the detail fetch fails
     * (offline, transient 429) so navigation still happens.
     */
    fun openDetail(item: CatalogItem) {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
                ?.takeIf { it.type == SourceType.GUTENBERG }
                ?: return@launch
            val enriched = runCatching { activeCatalog()?.getItem(item.id) }.getOrNull() ?: item
            libraryItemUpserter.upsert(source.id, enriched)
            _openDetailEvents.emit(OpenDetailEvent(itemId = item.id))
        }
    }

    private suspend fun refreshOnce() {
        val catalog = activeCatalog() ?: return
        _isLoading.value = true
        _error.value = null
        try {
            val q = _query.value.trim()
            val facetKey = _selectedFacet.value
            logger.d(LogChannel.Gutenberg) {
                "refresh rootId=$rootId query='${q}' facet=$facetKey page=0"
            }
            val result = if (q.isNotEmpty()) {
                catalog.search(rootId = rootId, query = q, page = 0, pageSize = PAGE_SIZE)
            } else {
                val facet = facetKey?.let { FacetSelection(it) }
                catalog.browse(rootId = rootId, page = 0, pageSize = PAGE_SIZE, facet = facet)
            }
            logger.d(LogChannel.Gutenberg) { "refresh ok size=${result.size}" }
            _items.value = result
            currentPage = 0
            _hasMore.value = result.size >= GUTENDEX_PAGE_SIZE
        } catch (ce: CancellationException) {
            // A newer refresh cancelled this one — do NOT overwrite items/error with a stale
            // failure. The successor coroutine is already carrying the fresh state. Rethrow so
            // structured concurrency stays intact.
            throw ce
        } catch (t: Throwable) {
            logger.d(LogChannel.Gutenberg) {
                "refresh error facet=${_selectedFacet.value} query='${_query.value}' " +
                    "type=${t::class.simpleName} msg=${t.message} " +
                    "cause=${t.cause?.let { "${it::class.simpleName}:${it.message}" }}"
            }
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
            val existingIds = _items.value.mapTo(HashSet()) { it.id }
            val appended = next.filter { it.id !in existingIds }
            if (appended.isNotEmpty()) {
                _items.value = _items.value + appended
            }
            currentPage = nextPage
            _hasMore.value = next.size >= GUTENDEX_PAGE_SIZE
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Pagination failure isn't fatal — leave hasMore true so a subsequent scroll retries.
        } finally {
            _isPaging.value = false
        }
    }

    private companion object {
        /**
         * Requested slice size. Gutendex returns 32 items per page; we ask for 32 so page N of
         * our pagination = page N of Gutendex's, and a short response is a genuine end-of-list
         * signal.
         */
        const val PAGE_SIZE = 32

        /** The exact page size Gutendex ships — used to detect a short (final) page. */
        const val GUTENDEX_PAGE_SIZE = 32

        /**
         * How long to wait after a facet-chip tap before firing the network fetch. Small enough
         * that a deliberate single tap feels instant; large enough that rapid taps collapse
         * into a single request for the final selection.
         */
        const val FACET_DEBOUNCE_MS: Long = 250L
    }
}

/**
 * Map network failures to messages users can act on. The raw OkHttp/DNS text
 * (`Unable to resolve host "gutendex.com": No address associated with hostname`) leaks
 * implementation and reads like a crash; offline is the by-far common cause.
 */
internal fun friendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach Project Gutenberg. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
