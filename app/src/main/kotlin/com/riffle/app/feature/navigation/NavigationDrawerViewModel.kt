package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NavigationDrawerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val libraryRepository: LibraryRepository,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
) : ViewModel() {

    val allServers: StateFlow<List<Server>> = serverRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<Server?> = allServers
        .map { servers -> servers.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val visibleLibraries: StateFlow<List<Library>> = activeServer
        .filterNotNull()
        .flatMapLatest { server ->
            combine(
                libraryRepository.observeLibraries(),
                visibilityStore.hiddenLibraryIds(server.id),
            ) { libraries, hiddenIds ->
                libraries.filter { it.id !in hiddenIds }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastActiveLibraryId = MutableStateFlow<String?>(null)

    fun setActiveLibrary(libraryId: String) {
        _lastActiveLibraryId.value = libraryId
    }

    private val _redirectToLibrary = MutableSharedFlow<Library>(extraBufferCapacity = 1)

    // Emits the next visible library when the active library is hidden; caller must call setActiveLibrary after acting on it.
    val redirectToLibrary: Flow<Library> = _redirectToLibrary

    init {
        viewModelScope.launch {
            visibleLibraries.collect { visible ->
                val lastId = _lastActiveLibraryId.value ?: return@collect
                if (visible.isNotEmpty() && visible.none { it.id == lastId }) {
                    _redirectToLibrary.emit(visible.first())
                }
            }
        }
    }

    fun setActiveServer(serverId: String) {
        viewModelScope.launch { serverRepository.setActive(serverId) }
    }
}
