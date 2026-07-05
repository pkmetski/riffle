package com.riffle.app.feature.annotations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.data.AnnotatedBook
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.domain.ServerRepository
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
 * Backs the Annotations View library grid — books with at least one live highlight on the active
 * server. Follows the active-server derivation shape used by `NavigationDrawerViewModel`
 * (Storyteller servers are excluded there too, but they never carry annotations to begin with
 * since annotation sync is ABS-server-scoped).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnnotationsListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val repo: AnnotationsLibraryRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val activeServerId: kotlinx.coroutines.flow.Flow<String?> = serverRepository.observeAll()
        .map { servers ->
            servers.firstOrNull { it.isActive && it.serverType != ServerType.STORYTELLER }?.id
        }

    val state: StateFlow<AnnotationsListUiState> = activeServerId
        .flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(AnnotationsListUiState(loading = false))
            } else {
                repo.observeAnnotatedBooks(serverId)
                    .map { books -> AnnotationsListUiState(loading = false, books = books) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationsListUiState())

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
        }
    }
}
