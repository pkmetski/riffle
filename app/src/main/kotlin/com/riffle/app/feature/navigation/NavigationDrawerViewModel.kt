package com.riffle.app.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.orderLibraries
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.isReadaloud
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
    private val serverRepository: ServerRepository,
    private val libraryRepository: LibraryRepository,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val orderStore: LibraryOrderPreferencesStore,
    private val connectivityObserver: ConnectivityObserver,
    nowPlayingNavigator: NowPlayingNavigator,
    private val nowPlayingStore: NowPlayingStore,
) : ViewModel() {

    // A media-notification tap asks to open the active player; MainScreen reads [currentNowPlaying].
    val openNowPlayingRequests: Flow<Unit> = nowPlayingNavigator.events

    fun currentNowPlaying(): NowPlaying? = nowPlayingStore.current

    // Storyteller is a Settings-only readaloud backend (ADR 0026): it never appears in the Server
    // Switcher and can never become the active browsable Server.
    val allServers: StateFlow<List<Server>> = serverRepository.observeAll()
        .map { servers -> servers.filter { it.serverType != ServerType.STORYTELLER } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<Server?> = allServers
        .map { servers -> servers.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val versionsCache = mutableMapOf<String, String>()
    private val _serverVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverVersions: StateFlow<Map<String, String>> = _serverVersions.asStateFlow()

    val visibleLibraries: StateFlow<List<Library>> = activeServer
        .filterNotNull()
        .flatMapLatest { server ->
            combine(
                libraryRepository.observeLibraries(),
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
    }

    private val _redirectToLibrary = MutableSharedFlow<Library>(extraBufferCapacity = 1)

    // Emits the next visible library when the active library is hidden; caller must call setActiveLibrary after acting on it.
    val redirectToLibrary: Flow<Library> = _redirectToLibrary

    init {
        viewModelScope.launch {
            // Re-attempt whenever the server list changes or connectivity flips.
            // We don't gate on isOnline=true because LAN-only servers (e.g. self-hosted
            // ABS on a local network) are reachable even when the OS reports no
            // validated internet; getServerVersion() returns null on failure.
            combine(allServers, connectivityObserver.isOnline) { servers, _ -> servers }
                .collect { servers ->
                    servers.forEach { server ->
                        if (server.id in versionsCache) return@forEach
                        val version = serverRepository.getServerVersion(server.id) ?: return@forEach
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

    fun setActiveServer(serverId: String) {
        viewModelScope.launch { serverRepository.setActive(serverId) }
    }
}
