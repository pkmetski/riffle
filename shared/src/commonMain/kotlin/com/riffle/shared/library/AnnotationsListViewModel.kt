package com.riffle.shared.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotatedBook
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AnnotationsListUiState(
    val loading: Boolean = true,
    val books: List<AnnotatedBook> = emptyList(),
)

/**
 * Backs the per-Library Annotations tab in the Library Tab Bar — books with at least one live
 * highlight on the active server, scoped to [libraryId], following the same pattern as
 * `LibraryItemsViewModel`. Storyteller services are excluded (they never carry annotations since
 * annotation sync is ABS-server-scoped).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsListViewModel constructor(
    private val libraryId: String,
    private val sourceRepository: SourceRepository,
    private val repo: AnnotationsLibraryRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val activeServerId = sourceRepository.observeAll()
        .map { sources ->
            sources.firstOrNull { it.isActive && it.serverType != ServerType.STORYTELLER_SERVICE }?.id
        }

    val state: StateFlow<AnnotationsListUiState> = activeServerId
        .flatMapLatest { sourceId ->
            if (sourceId == null) {
                flowOf(AnnotationsListUiState(loading = false))
            } else {
                repo.observeAnnotatedBooks(sourceId, libraryId)
                    .map { books -> AnnotationsListUiState(loading = false, books = books) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationsListUiState())

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val source = sourceRepository.getActive()
            if (source != null) authToken = tokenStorage.getToken(source.id) ?: ""
        }
    }
}
