package com.riffle.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val crashReportRepository: CrashReportRepository,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val serverRepository: ServerRepository,
    private val libraryRepository: LibraryRepository,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val readaloudReviewRepository: ReadaloudReviewRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    val lastCrashReport: CrashReport? = crashReportRepository.getLastCrashReport()

    val globalFormattingPreferences: StateFlow<FormattingPreferences> =
        formattingPreferencesStore.preferences
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FormattingPreferences())

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val servers: StateFlow<List<Server>> = serverRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ids of the configured Storyteller servers, feeding the per-server readaloud summaries. */
    private val storytellerServerIds: StateFlow<List<String>> = servers
        .map { list -> list.filter { it.serverType == ServerType.STORYTELLER }.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Readaloud-matches counts (confirmed / pending / unmatched) for each Storyteller server. */
    val readaloudSummaries: StateFlow<Map<String, ReadaloudMatchSummary>> = storytellerServerIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                MutableStateFlow(emptyMap<String, ReadaloudMatchSummary>())
            } else {
                combine(
                    ids.map { id ->
                        readaloudReviewRepository.observeReview(id).map { review ->
                            id to ReadaloudMatchSummary(
                                confirmedCount = review.confirmed.size,
                                pendingCount = review.pending.size,
                                unmatchedCount = review.unmatched.size,
                            )
                        }
                    }
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val versionsCache = mutableMapOf<String, String>()
    private val _serverVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverVersions: StateFlow<Map<String, String>> = _serverVersions.asStateFlow()

    init {
        viewModelScope.launch {
            // Re-attempt whenever the server list changes or connectivity flips.
            // See NavigationDrawerViewModel for why we don't gate on isOnline=true.
            combine(servers, connectivityObserver.isOnline) { srvs, _ -> srvs }
                .collect { srvs ->
                    srvs.forEach { server ->
                        if (server.id in versionsCache) return@forEach
                        val version = serverRepository.getServerVersion(server.id) ?: return@forEach
                        versionsCache[server.id] = version
                        _serverVersions.value = versionsCache.toMap()
                    }
                }
        }
    }

    private val absServers: StateFlow<List<Server>> = servers
        .map { list -> list.filter { it.serverType == ServerType.AUDIOBOOKSHELF } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Library visibility items for each Audiobookshelf server, keyed by server id. Libraries are
     * read from the local DB per server, so a server need not be active to manage its libraries.
     */
    val libraryUiItemsByServer: StateFlow<Map<String, List<LibraryUiItem>>> = absServers
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                MutableStateFlow(emptyMap<String, List<LibraryUiItem>>())
            } else {
                combine(
                    list.map { server ->
                        combine(
                            libraryRepository.observeLibraries(server.id),
                            visibilityStore.hiddenLibraryIds(server.id),
                        ) { libraries, hiddenIds ->
                            val visibleCount = libraries.count { it.id !in hiddenIds }
                            server.id to libraries.map { lib ->
                                val isVisible = lib.id !in hiddenIds
                                LibraryUiItem(
                                    library = lib,
                                    isVisible = isVisible,
                                    switchEnabled = !isVisible || visibleCount > 1,
                                )
                            }
                        }
                    }
                ) { pairs -> pairs.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _navigationEvents = Channel<SettingsNavEvent>(Channel.CONFLATED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    fun updateGlobalFormatting(prefs: FormattingPreferences) {
        viewModelScope.launch { formattingPreferencesStore.update(prefs) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setInvertVolumeKeys(value) }
    }

    fun removeServer(serverId: String) {
        viewModelScope.launch {
            val current = servers.value
            val removing = current.firstOrNull { it.id == serverId } ?: return@launch
            serverRepository.remove(serverId)
            if (removing.isActive) {
                val next = current.firstOrNull { it.id != serverId }
                if (next != null) {
                    serverRepository.setActive(next.id)
                } else {
                    _navigationEvents.send(SettingsNavEvent.NavigateToAddServer)
                }
            }
        }
    }

    fun openReadaloudMatches(serverId: String) {
        viewModelScope.launch {
            _navigationEvents.send(SettingsNavEvent.NavigateToReadaloudMatches(serverId))
        }
    }

    fun setLibraryVisible(serverId: String, libraryId: String, visible: Boolean) {
        viewModelScope.launch {
            if (visible) visibilityStore.showLibrary(serverId, libraryId)
            else visibilityStore.hideLibrary(serverId, libraryId)
        }
    }
}

/** Counts shown in a Storyteller server's expanded "Readaloud matches" summary. */
data class ReadaloudMatchSummary(
    val confirmedCount: Int,
    val pendingCount: Int,
    val unmatchedCount: Int,
)
