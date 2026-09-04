package com.riffle.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Library
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.isReadaloud
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModel(
    private val sourceRepository: SourceRepository,
    private val libraryObserver: LibraryObserver,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val lastOpenedLibraryStore: LastOpenedLibraryStore,
) : ViewModel() {

    val allServers: StateFlow<List<Source>> = sourceRepository.observeAll()
        .map { servers -> servers.filter { it.serverType != ServerType.STORYTELLER_SERVICE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<Source?> = allServers
        .map { servers -> servers.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val visibleLibraries: StateFlow<List<Library>> = activeServer
        .filterNotNull()
        .flatMapLatest { server ->
            combine(
                libraryObserver.observeLibraries(),
                visibilityStore.hiddenLibraryIds(server.id),
            ) { libraries, hiddenIds ->
                libraries.filter { it.id !in hiddenIds && !it.isReadaloud }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastActiveLibraryId = MutableStateFlow<String?>(null)

    private val _redirectToLibrary = MutableSharedFlow<Library>(extraBufferCapacity = 1)

    /** Emits when the active library is hidden; caller must navigate away and call [setActiveLibrary]. */
    val redirectToLibrary: Flow<Library> = _redirectToLibrary

    fun setActiveLibrary(libraryId: String) {
        _lastActiveLibraryId.value = libraryId
        viewModelScope.launch {
            val sourceId = sourceRepository.getActive()?.id ?: return@launch
            lastOpenedLibraryStore.setLastOpenedLibrary(sourceId, libraryId)
        }
    }

    fun setActiveServer(sourceId: String) {
        viewModelScope.launch { sourceRepository.setActive(sourceId) }
    }

    init {
        viewModelScope.launch {
            activeServer
                .filterNotNull()
                .drop(1)
                .collect { _lastActiveLibraryId.value = null }
        }
        viewModelScope.launch {
            visibleLibraries.collect { visible ->
                val lastId = _lastActiveLibraryId.value ?: return@collect
                if (visible.isNotEmpty() && visible.none { it.id == lastId }) {
                    _redirectToLibrary.emit(visible.first())
                }
            }
        }
    }
}
