package com.riffle.app.feature.annotations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotatedBook
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnnotationsListUiState(
    val loading: Boolean = true,
    val books: List<AnnotatedBook> = emptyList(),
)

/**
 * Backs the per-Library Annotations tab in the Library Tab Bar — books with at least one live
 * highlight on the active server, scoped to [libraryId] read from `SavedStateHandle`, following the
 * same pattern as `LibraryItemsViewModel`. Follows the active-server derivation shape used by
 * `NavigationDrawerViewModel` (Storyteller services are excluded there too, but they never carry
 * annotations to begin with since annotation sync is ABS-server-scoped).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnnotationsListViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val repo: AnnotationsLibraryRepository,
    private val tokenStorage: TokenStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val libraryId: String = savedStateHandle.get<String>("libraryId") ?: ""

    private val activeServerId: kotlinx.coroutines.flow.Flow<String?> = sourceRepository.observeAll()
        .map { servers ->
            servers.firstOrNull { it.isActive && it.serverType != ServerType.STORYTELLER_SERVICE }?.id
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
            val server = sourceRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
        }
    }
}
