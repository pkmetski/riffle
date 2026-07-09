package com.riffle.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.orderLibraries
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.Source
import com.riffle.core.domain.ServerType
import com.riffle.app.feature.annotationsync.AnnotationSyncKind
import com.riffle.app.feature.annotationsync.deriveAnnotationSyncKind
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.data.localfiles.LocalFilesFolderHealthChecker
import com.riffle.core.data.localfiles.LocalFilesFolderRepository
import com.riffle.core.data.localfiles.LocalFilesScanner
import com.riffle.core.data.localfiles.LocalFilesSourceInstaller
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.SourceRepository
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

/** UI state for the at-a-glance "WebDAV" row in Settings (ADR 0036). */
data class AnnotationSyncRowState(
    val badge: Badge,
    val headline: String,
    val sub: String,
    val subTone: Tone,
) {
    enum class Badge { Local, Synced, Pending, Error }
    enum class Tone { Normal, Pending, Error }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val crashReportRepository: CrashReportRepository,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val sourceRepository: SourceRepository,
    private val libraryObserver: LibraryObserver,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
    private val orderStore: LibraryOrderPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val appThemeStore: AppThemeStore,
    private val readaloudReviewRepository: ReadaloudReviewRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val appUpdateRepository: AppUpdateRepository,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val localFilesFolderDao: LocalFilesFolderDao,
    private val localFilesFolderRepository: LocalFilesFolderRepository,
    private val localFilesScanner: LocalFilesScanner,
    private val localFilesSourceInstaller: LocalFilesSourceInstaller,
    private val localFilesFolderHealthChecker: LocalFilesFolderHealthChecker,
    annotationSyncConfigStore: com.riffle.core.domain.AnnotationSyncConfigStore,
    annotationSyncStatusStore: AnnotationSyncStatusStore,
    annotationDao: AnnotationDao,
) : ViewModel() {

    /** At-a-glance row state for the main Settings "WebDAV" entry (ADR 0036). */
    val annotationSyncRow: StateFlow<AnnotationSyncRowState> = combine(
        annotationSyncConfigStore.observe(),
        annotationSyncStatusStore.lastCycleOutcome,
        annotationDao.observePendingBookCountAcrossAll(),
    ) { config, outcome, pendingCount ->
        deriveRow(config, outcome, pendingCount)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        deriveRow(null, CycleOutcome.NeverRun, 0),
    )

    private fun deriveRow(
        config: com.riffle.core.domain.AnnotationSyncConfig?,
        outcome: CycleOutcome,
        pendingCount: Int,
    ): AnnotationSyncRowState {
        val kind = deriveAnnotationSyncKind(config, outcome, pendingCount)
        val badge = when (kind) {
            AnnotationSyncKind.Local -> AnnotationSyncRowState.Badge.Local
            AnnotationSyncKind.Synced -> AnnotationSyncRowState.Badge.Synced
            AnnotationSyncKind.Pending -> AnnotationSyncRowState.Badge.Pending
            AnnotationSyncKind.Error -> AnnotationSyncRowState.Badge.Error
        }
        val subTone = when (kind) {
            AnnotationSyncKind.Local, AnnotationSyncKind.Synced -> AnnotationSyncRowState.Tone.Normal
            AnnotationSyncKind.Pending -> AnnotationSyncRowState.Tone.Pending
            AnnotationSyncKind.Error -> AnnotationSyncRowState.Tone.Error
        }
        val identity = config?.let { "${it.username}@${shortHost(it.baseUrl)}" }
        // NeverRun outranks a positive pending count so the row keeps saying
        // "Waiting for first sync…" for a freshly-configured install — matching pre-refactor
        // behavior. The kind is still Pending either way, so the badge stays in sync with the
        // banner via [deriveAnnotationSyncKind].
        val sub = when {
            config == null -> "Not configured · tap to set up a WebDAV server"
            outcome is CycleOutcome.NeverRun -> "Waiting for first sync…"
            outcome is CycleOutcome.Failed.Auth -> "Authentication failed · tap to re-enter credentials"
            outcome is CycleOutcome.Failed.Tls -> "TLS error · tap to check server URL"
            outcome is CycleOutcome.Failed.Server -> "Source error (HTTP ${outcome.code}) · will retry automatically"
            outcome is CycleOutcome.Failed.Unknown -> "Sync failed · will retry automatically"
            outcome is CycleOutcome.Failed.Network && pendingCount > 0 ->
                "$pendingCount book(s) pending · will sync when online"
            outcome is CycleOutcome.Failed.Network -> "Offline · will sync when connected"
            pendingCount > 0 -> "$pendingCount book(s) pending · will sync when online"
            else -> "Synced · $identity"
        }
        return AnnotationSyncRowState(badge, "WebDAV", sub, subTone)
    }

    private val _crashReports = MutableStateFlow(crashReportRepository.listCrashReports())
    /** All recorded crashes, newest first. Refreshed by [clearCrashReports] / [refreshCrashReports]. */
    val crashReports: StateFlow<List<CrashReport>> = _crashReports.asStateFlow()

    fun refreshCrashReports() {
        _crashReports.value = crashReportRepository.listCrashReports()
    }

    fun clearCrashReports() {
        crashReportRepository.clearAllCrashReports()
        _crashReports.value = emptyList()
    }

    /** Files backing the currently-listed reports — used by the Settings "Share" button to
     *  build an ACTION_SEND_MULTIPLE intent. Kept in the VM so the screen doesn't have to
     *  hold the repository. */
    fun crashReportFiles(): List<java.io.File> =
        crashReportRepository.resolveReportFiles(_crashReports.value.map { it.id })

    /** The currently installed app version, shown as the update row's subtitle. */
    val installedVersionName: String = BuildConfig.VERSION_NAME

    private val _appUpdateState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    /** Manually triggered from Settings: ask GitHub whether a newer release exists. */
    fun checkForUpdate() {
        if (_appUpdateState.value is AppUpdateUiState.Checking ||
            _appUpdateState.value is AppUpdateUiState.Downloading
        ) {
            return
        }
        _appUpdateState.value = AppUpdateUiState.Checking
        viewModelScope.launch {
            _appUpdateState.value = when (val result = appUpdateRepository.checkForUpdate(BuildConfig.VERSION_CODE)) {
                is UpdateCheckResult.UpToDate -> AppUpdateUiState.UpToDate
                is UpdateCheckResult.Failed -> AppUpdateUiState.Failed(result.message)
                is UpdateCheckResult.UpdateAvailable ->
                    AppUpdateUiState.UpdateAvailable(result.update.versionName, result.update)
            }
        }
    }

    /** Download the available update's APK and launch the system installer. */
    fun downloadAndInstallUpdate() {
        val available = _appUpdateState.value as? AppUpdateUiState.UpdateAvailable ?: return
        _appUpdateState.value = AppUpdateUiState.Downloading(0)
        viewModelScope.launch {
            appUpdateRepository.downloadAndInstall(available.update).collect { step ->
                _appUpdateState.value = when (step) {
                    is UpdateDownloadState.Downloading -> AppUpdateUiState.Downloading(step.percent)
                    is UpdateDownloadState.Installing -> AppUpdateUiState.Installing
                    is UpdateDownloadState.Failed -> AppUpdateUiState.Failed(step.message)
                }
            }
        }
    }

    val globalFormattingPreferences: StateFlow<FormattingPreferences> =
        formattingPreferencesStore.preferences
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FormattingPreferences())

    val readaloudPreferences: StateFlow<ReadaloudPreferences> =
        readaloudPreferencesStore.preferences
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadaloudPreferences())

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val defaultPlaybackSpeed: StateFlow<Float> = listeningPreferencesStore.defaultPlaybackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)

    val skipIntervalSeconds: StateFlow<Int> = listeningPreferencesStore.skipIntervalSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)

    val rewindIntervalSeconds: StateFlow<Int> = listeningPreferencesStore.rewindIntervalSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS)

    val rewindOnResumeSeconds: StateFlow<Int> = listeningPreferencesStore.rewindOnResumeSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)

    val appTheme: StateFlow<AppTheme> = appThemeStore.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTheme.System)

    val servers: StateFlow<List<Source>> = sourceRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The singleton LocalFiles Source row if one has been installed (there is at most one per
     * device — multi-folder lives inside it). Null before the first Add-Source LocalFiles pass.
     */
    val localFilesSource: StateFlow<Source?> = servers
        .map { list -> list.firstOrNull { it.type == com.riffle.core.domain.SourceType.LOCAL_FILES } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Configured folder rows under the LocalFiles Source, reactive. Empty when no source yet. */
    val localFilesFolders: StateFlow<List<LocalFilesFolderEntity>> = localFilesSource
        .flatMapLatest { source ->
            if (source == null) MutableStateFlow(emptyList())
            else localFilesFolderDao.observeForSource(source.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * (treeUri → isHealthy) for every configured LocalFiles folder. Unhealthy folders had their
     * persistable URI grant revoked by the user in system Settings; already-copied bytes stay
     * readable, but rescanning and picking up new files needs a re-pick.
     *
     * Re-derives every time the folder set changes so users returning from system Settings pick
     * up the fresh state without a manual refresh gesture.
     */
    val localFilesFolderHealth: StateFlow<Map<String, Boolean>> = localFilesFolders
        .map { folders -> localFilesFolderHealthChecker.healthFor(folders.map { it.treeUri }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Ids of the configured Storyteller servers, feeding the per-server readaloud summaries. */
    private val storytellerServerIds: StateFlow<List<String>> = servers
        .map { list -> list.filter { it.serverType == ServerType.STORYTELLER }.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Readaloud-matches counts (unmatched / suggested / partially matched / matched) per server. */
    val readaloudSummaries: StateFlow<Map<String, ReadaloudMatchSummary>> = storytellerServerIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                MutableStateFlow(emptyMap<String, ReadaloudMatchSummary>())
            } else {
                combine(
                    ids.map { id ->
                        readaloudReviewRepository.observeReview(id).map { review ->
                            val partiallyMatched = review.confirmed.count { it.isIncomplete }
                            id to ReadaloudMatchSummary(
                                unmatchedCount = review.unmatched.size,
                                suggestedCount = review.pending.size,
                                partiallyMatchedCount = partiallyMatched,
                                matchedCount = review.confirmed.size - partiallyMatched,
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
                        val version = sourceRepository.getSourceVersion(server.id) ?: return@forEach
                        versionsCache[server.id] = version
                        _serverVersions.value = versionsCache.toMap()
                    }
                }
        }
    }

    private val absServers: StateFlow<List<Source>> = servers
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
                            libraryObserver.observeLibraries(server.id),
                            visibilityStore.hiddenLibraryIds(server.id),
                            orderStore.libraryOrder(server.id),
                        ) { libraries, hiddenIds, order ->
                            val ordered = orderLibraries(libraries, order)
                            val visibleCount = ordered.count { it.id !in hiddenIds }
                            server.id to ordered.map { lib ->
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

    fun updateHighlightColor(color: HighlightColor) {
        viewModelScope.launch {
            readaloudPreferencesStore.update(ReadaloudPreferences(highlightColor = color))
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    fun setAppTheme(value: AppTheme) {
        viewModelScope.launch { appThemeStore.setAppTheme(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setInvertVolumeKeys(value) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { listeningPreferencesStore.setDefaultPlaybackSpeed(speed) }
    }

    fun setSkipIntervalSeconds(seconds: Int) {
        viewModelScope.launch { listeningPreferencesStore.setSkipIntervalSeconds(seconds) }
    }

    fun setRewindIntervalSeconds(seconds: Int) {
        viewModelScope.launch { listeningPreferencesStore.setRewindIntervalSeconds(seconds) }
    }

    fun setRewindOnResumeSeconds(seconds: Int) {
        viewModelScope.launch { listeningPreferencesStore.setRewindOnResumeSeconds(seconds) }
    }

    fun removeServer(sourceId: String) {
        viewModelScope.launch {
            val current = servers.value
            val removing = current.firstOrNull { it.id == sourceId } ?: return@launch
            sourceRepository.remove(sourceId)
            if (removing.isActive) {
                // Promote the next browsable server. A Storyteller Source is never browsable
                // (ADR 0026) and can never be active, so skip it when choosing the successor.
                val next = current.firstOrNull { it.id != sourceId && it.serverType != ServerType.STORYTELLER }
                if (next != null) {
                    sourceRepository.setActive(next.id)
                } else {
                    _navigationEvents.send(SettingsNavEvent.NavigateToAddSource)
                }
            }
        }
    }

    fun openReadaloudMatches(sourceId: String) {
        viewModelScope.launch {
            _navigationEvents.send(SettingsNavEvent.NavigateToReadaloudMatches(sourceId))
        }
    }

    fun setLibraryVisible(sourceId: String, libraryId: String, visible: Boolean) {
        viewModelScope.launch {
            if (visible) visibilityStore.showLibrary(sourceId, libraryId)
            else visibilityStore.hideLibrary(sourceId, libraryId)
        }
    }

    /** Persist a new full ordering of [sourceId]'s libraries after a drag-reorder in Settings. */
    fun setLibraryOrder(sourceId: String, orderedLibraryIds: List<String>) {
        viewModelScope.launch { orderStore.setLibraryOrder(sourceId, orderedLibraryIds) }
    }

    private fun shortHost(rawUrl: String): String =
        runCatching { java.net.URI(rawUrl).host ?: rawUrl }.getOrDefault(rawUrl)

    // region LocalFiles folder management

    /**
     * Remove one configured LocalFiles folder from the singleton LocalFiles Source. Releases the
     * SAF grant, deletes the folder row, then runs a scan so the sweep-stale pass hard-deletes
     * every file that lived only in this folder (identity-hashed rows shared with another folder
     * survive automatically). Callers confirm via a dialog.
     */
    fun removeLocalFolder(treeUri: String) {
        val source = localFilesSource.value ?: return
        viewModelScope.launch {
            localFilesFolderRepository.removeFolder(source.id, treeUri)
            localFilesScanner.scan(source.id)
        }
    }

    /**
     * Remove the entire LocalFiles Source (all folders, all copied-in files). Wired through the
     * standard [removeServer] path so the cascade — DB, tokens, files-on-disk — stays uniform.
     */
    fun removeLocalFilesSource() {
        localFilesSource.value?.id?.let { removeServer(it) }
    }

    // endregion
}

/** Counts shown in a Storyteller server's expanded "Readaloud matches" summary (gradient order). */
data class ReadaloudMatchSummary(
    val unmatchedCount: Int,
    val suggestedCount: Int,
    val partiallyMatchedCount: Int,
    val matchedCount: Int,
)

/** Drives the "App version" settings row: an inline status that the user advances by tapping. */
sealed interface AppUpdateUiState {
    /** No check run yet (or it was reset). */
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class UpdateAvailable(
        val versionName: String,
        val update: com.riffle.core.domain.AvailableUpdate,
    ) : AppUpdateUiState
    data class Downloading(val percent: Int) : AppUpdateUiState
    /** APK downloaded; the system installer has been launched. */
    data object Installing : AppUpdateUiState
    data class Failed(val message: String) : AppUpdateUiState
}
