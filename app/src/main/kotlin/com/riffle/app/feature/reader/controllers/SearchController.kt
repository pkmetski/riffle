package com.riffle.app.feature.reader.controllers

import com.riffle.app.feature.reader.session.OrchestratorScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService

/**
 * Owns search execution, debounce, result navigation. Lifted from EpubReaderViewModel
 * as part of the VM split (#303).
 *
 * Note: a single [Publication] import is allowed by the plan because the controller needs to call
 * [Publication.findService] to reach the [SearchService]. Keep surface minimal — no other
 * Readium types appear in the public API beyond [Locator] (for navigation channel and results).
 *
 * MUST NOT import android.webkit.* or ContinuousReaderView.
 */
class SearchController @AssistedInject constructor(
    @Assisted private val scope: OrchestratorScope,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): SearchController
    }

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<Locator>>(emptyList())
    val searchResults: StateFlow<List<Locator>> = _searchResults

    private val _currentSearchIndex = MutableStateFlow(-1)
    val currentSearchIndex: StateFlow<Int> = _currentSearchIndex

    private val _searchNavigationChannel = Channel<Locator>(Channel.BUFFERED)
    val searchNavigationEvents: Flow<Locator> = _searchNavigationChannel.receiveAsFlow()

    private var publication: Publication? = null
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    /**
     * Bind to the open [Publication]. Call once after the book is ready. Passing null is safe
     * (search will produce empty results). Re-binding clears any in-flight search.
     */
    fun bind(publication: Publication?) {
        this.publication = publication
        // Reset search state for the new book.
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        searchJob?.cancel()
        debounceJob?.cancel()

        @OptIn(FlowPreview::class)
        debounceJob = scope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    searchJob?.cancel()
                    if (query.length < 2) {
                        _searchResults.value = emptyList()
                        _currentSearchIndex.value = -1
                        return@collect
                    }
                    searchJob = launch { performSearch(query) }
                }
        }
    }

    fun openSearch() {
        _isSearchActive.value = true
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        searchJob?.cancel()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun nextSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val next = (_currentSearchIndex.value + 1).coerceAtMost(results.size - 1)
        _currentSearchIndex.value = next
        _searchNavigationChannel.trySend(results[next])
    }

    fun prevSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prev = (_currentSearchIndex.value - 1).coerceAtLeast(0)
        _currentSearchIndex.value = prev
        _searchNavigationChannel.trySend(results[prev])
    }

    // ---- Internal test helper ------------------------------------------------------------------

    /**
     * Test-only: directly injects results + index, bypassing publication.search().
     * Not part of the public contract — only visible within the package and tests.
     */
    internal fun setResultsForTest(results: List<Locator>, startIndex: Int) {
        _searchResults.value = results
        _currentSearchIndex.value = startIndex
        if (results.isNotEmpty()) _searchNavigationChannel.trySend(results[startIndex])
    }

    // ---- Private -------------------------------------------------------------------------------

    private suspend fun performSearch(query: String) {
        // Drain any pending navigation events buffered from the previous search.
        while (_searchNavigationChannel.tryReceive().isSuccess) { /* drain */ }
        val pub = publication ?: return
        val service = pub.findService(SearchService::class)
        if (service == null) {
            _searchResults.value = emptyList()
            _currentSearchIndex.value = -1
            return
        }
        val results = try {
            withContext(Dispatchers.IO) {
                val iterator = service.search(query)
                val acc = mutableListOf<Locator>()
                try {
                    while (true) {
                        val pageResult = iterator.next()
                        if (pageResult.isFailure) continue
                        val page = pageResult.getOrNull() ?: break
                        acc.addAll(page.locators)
                    }
                } finally {
                    iterator.close()
                }
                acc
            }
        } catch (_: OutOfMemoryError) {
            emptyList()
        }
        _searchResults.value = results
        if (results.isEmpty()) {
            _currentSearchIndex.value = -1
        } else {
            _currentSearchIndex.value = 0
            _searchNavigationChannel.trySend(results[0])
        }
    }
}
