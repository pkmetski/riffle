package com.riffle.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.domain.AnnotationsLibraryRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModel constructor(
    private val libraryObserver: LibraryObserver,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val toReadRepository: ToReadRepository,
    private val annotationsLibraryRepository: AnnotationsLibraryRepository,
) : ViewModel() {

    val inProgress: StateFlow<List<LibraryItem>> =
        libraryObserver.observeInProgressItemsAllSources()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val continueSeries: StateFlow<List<LibraryItem>> =
        libraryObserver.observeContinueSeriesItemsAllSources()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Aggregated To Read items across all sources and their libraries. */
    val toRead: StateFlow<List<LibraryItem>> =
        sourceRepository.observeAll().flatMapLatest { sources ->
            if (sources.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val allLibrariesFlow = combine(
                sources.map { libraryObserver.observeLibraries(it.id) },
            ) { arrays -> arrays.flatMap { it } }
            allLibrariesFlow.flatMapLatest { libraries ->
                if (libraries.isEmpty()) return@flatMapLatest flowOf(emptyList())
                val perLibrary = libraries.map { library ->
                    combine(
                        toReadRepository.observeToReadItemIds(library.id),
                        libraryObserver.observeLibraryItems(library.id),
                    ) { ids, items -> items.filter { it.id in ids } }
                }
                combine(perLibrary) { arrays -> arrays.flatMap { it } }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val annotations: StateFlow<List<AnnotatedBook>> =
        annotationsLibraryRepository.observeAnnotatedBooksAllSources()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All configured sources — used by the composable to build the source-name badge map. */
    val sources: StateFlow<List<com.riffle.core.models.Source>> =
        sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Maps sourceId → auth token for authenticated cover image loading. */
    var authTokenMap: Map<String, String> by mutableStateOf(emptyMap())
        private set

    init {
        viewModelScope.launch {
            val sources = sourceRepository.observeAll().stateIn(viewModelScope).value
            authTokenMap = coroutineScope {
                sources.associate { source ->
                    val token = async { tokenStorage.getToken(source.id) ?: "" }
                    source.id to token.await()
                }
            }
        }
        // Refresh tokens whenever the source list changes.
        viewModelScope.launch {
            sourceRepository.observeAll().collect { sources ->
                authTokenMap = coroutineScope {
                    sources.associate { source ->
                        val token = async { tokenStorage.getToken(source.id) ?: "" }
                        source.id to token.await()
                    }
                }
            }
        }
    }
}
