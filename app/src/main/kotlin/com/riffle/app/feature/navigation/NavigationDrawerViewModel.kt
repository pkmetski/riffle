package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.orderLibraries
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.isReadaloud
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.app.playback.NowPlaying
import com.riffle.app.playback.NowPlayingNavigator
import com.riffle.app.playback.NowPlayingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NavigationDrawerViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val libraryObserver: LibraryObserver,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val orderStore: LibraryOrderPreferencesStore,
    private val lastOpenedLibraryStore: LastOpenedLibraryStore,
    private val connectivityObserver: ConnectivityObserver,
    private val catalogRegistry: CatalogRegistry,
    nowPlayingNavigator: NowPlayingNavigator,
    private val nowPlayingStore: NowPlayingStore,
) : ViewModel() {

    // A media-notification tap asks to open the active player; MainScreen reads [currentNowPlaying].
    val openNowPlayingRequests: Flow<Unit> = nowPlayingNavigator.events

    fun currentNowPlaying(): NowPlaying? = nowPlayingStore.current

    // Storyteller is a Settings-only readaloud backend (ADR 0026): it never appears in the Source
    // Switcher and can never become the active browsable Source.
    val allServers: StateFlow<List<Source>> = sourceRepository.observeAll()
        .map { servers -> servers.filter { it.serverType != ServerType.STORYTELLER_SERVICE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<Source?> = allServers
        .map { servers -> servers.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Capability-gated UI (issue #439) reads this to hide surfaces the active Source can't
    // support. Null before the first Source loads or when the active Source can't yield a
    // Catalog (missing credentials, unregistered factory). Composables treat null as "hide".
    val activeCatalog: StateFlow<Catalog?> = activeServer
        .map { source -> source?.let { catalogRegistry.forSource(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val versionsCache = mutableMapOf<String, String>()
    private val _serverVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverVersions: StateFlow<Map<String, String>> = _serverVersions.asStateFlow()

    val visibleLibraries: StateFlow<List<Library>> = activeServer
        .filterNotNull()
        .flatMapLatest { server ->
            combine(
                libraryObserver.observeLibraries(),
                visibilityStore.hiddenLibraryIds(server.id),
                orderStore.libraryOrder(server.id),
            ) { libraries, hiddenIds, order ->
                // Exclude hidden libraries and the never-browsable Readaloud namespace row (ADR 0026),
                // then apply the user's custom per-server order (alphabetical fallback for the rest).
                val visible = libraries.filter { it.id !in hiddenIds && !it.isReadaloud }
                orderLibraries(visible, order)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastActiveLibraryId = MutableStateFlow<String?>(null)

    fun setActiveLibrary(libraryId: String) {
        _lastActiveLibraryId.value = libraryId
        // Remember it per-server so app start reopens this library (see HomeViewModel). Resolve the
        // active server authoritatively from the repository — the same source getStartDestination
        // reads. The activeServer StateFlow can lag a just-committed server switch, which would
        // persist this library under the previous server's key (or drop it when still null on cold
        // start).
        viewModelScope.launch {
            val sourceId = sourceRepository.getActive()?.id ?: return@launch
            lastOpenedLibraryStore.setLastOpenedLibrary(sourceId, libraryId)
        }
    }

    private val _redirectToLibrary = MutableSharedFlow<Library>(extraBufferCapacity = 1)

    // Emits the next visible library when the active library is hidden; caller must call setActiveLibrary after acting on it.
    val redirectToLibrary: Flow<Library> = _redirectToLibrary

    init {
        viewModelScope.launch {
            // Re-attempt whenever the server list changes or connectivity flips.
            // We don't gate on isOnline=true because LAN-only servers (e.g. self-hosted
            // ABS on a local network) are reachable even when the OS reports no
            // validated internet; getSourceVersion() returns null on failure.
            combine(allServers, connectivityObserver.isOnline) { servers, _ -> servers }
                .collect { servers ->
                    servers.forEach { server ->
                        if (server.id in versionsCache) return@forEach
                        val version = sourceRepository.getSourceVersion(server.id) ?: return@forEach
                        versionsCache[server.id] = version
                        _serverVersions.value = versionsCache.toMap()
                    }
                }
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

    fun setActiveServer(sourceId: String) {
        viewModelScope.launch { sourceRepository.setActive(sourceId) }
    }
}
