package com.riffle.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.server.AddSourceBackend
import com.riffle.app.feature.settings.panels.AutoScrollSettingsPanel
import com.riffle.app.feature.settings.panels.BehaviorSettingsPanel
import com.riffle.app.feature.settings.panels.CadenceSettingsPanel
import com.riffle.app.feature.settings.panels.DisplaySettingsPanel
import com.riffle.app.feature.settings.panels.FormattingSettingsPanel
import com.riffle.app.feature.settings.panels.ListeningPreferencesPanel
import com.riffle.app.feature.settings.sections.AnnotationsSyncSection
import com.riffle.app.feature.settings.sections.AppVersionSection
import com.riffle.app.feature.settings.sections.AppearanceSection
import com.riffle.app.feature.settings.sections.DiagnosticsSection
import com.riffle.app.feature.settings.sections.ListeningSection
import com.riffle.app.feature.settings.sections.PacingSection
import com.riffle.app.feature.settings.sections.ReadaloudSection
import com.riffle.app.feature.settings.sections.ReadingSection
import com.riffle.app.feature.settings.sections.SourcesSection
import com.riffle.app.ui.TabletContentWidthContainer

/** Subject line used when sharing a single crash report. Kept here so tests can pin the format. */
internal fun crashReportShareSubject(timestamp: String): String =
    "Riffle crash report ($timestamp)"

/**
 * Global Settings screen. Composes one section per top-level category, each in its own file under
 * [com.riffle.app.feature.settings.sections]. All bottom-sheet drill-in panels (Formatting,
 * Display, Behavior, Auto-Scroll, Cadence, Listening) share a single [SettingsPanel] state so
 * only one panel can be open at a time; nav-to-screen drill-ins (Readaloud, WebDAV, Debug logs,
 * Readaloud matches, sync Maintenance) go through the standard NavController.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onNavigateToAddSource: (backend: AddSourceBackend, editId: String?) -> Unit,
    onNavigateToAddSourcePicker: () -> Unit = { onNavigateToAddSource(AddSourceBackend.AUDIOBOOKSHELF, null) },
    onNavigateToAddLocalFolder: () -> Unit = {},
    onNavigateToReadaloudSettings: () -> Unit = {},
    onNavigateToAnnotationsSyncSettings: () -> Unit = {},
    onNavigateToDebugLogs: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val crashReports by viewModel.crashReports.collectAsState()
    val globalFormatting by viewModel.globalFormattingPreferences.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val localFilesSource by viewModel.localFilesSource.collectAsState()
    val localFilesFolders by viewModel.localFilesFolders.collectAsState()
    val localFilesFolderHealth by viewModel.localFilesFolderHealth.collectAsState()
    val singletonWebSources by viewModel.singletonWebSources.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val libraryItemsBySource by viewModel.libraryUiItemsBySource.collectAsState()
    val readaloudSummaries by viewModel.readaloudSummaries.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val defaultPlaybackSpeed by viewModel.defaultPlaybackSpeed.collectAsState()
    val skipIntervalSeconds by viewModel.skipIntervalSeconds.collectAsState()
    val rewindIntervalSeconds by viewModel.rewindIntervalSeconds.collectAsState()
    val rewindOnResumeSeconds by viewModel.rewindOnResumeSeconds.collectAsState()
    val annotationSyncRow by viewModel.annotationSyncRow.collectAsState()
    val expandedServers = remember { mutableStateMapOf<String, Boolean>() }
    val expandedCrashes = remember { mutableStateMapOf<String, Boolean>() }
    var openPanel by remember { mutableStateOf<SettingsPanel?>(null) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SettingsNavEvent.NavigateToAddSource -> onNavigateToAddSourcePicker()
            }
        }
    }

    // Returning from system Settings after revoking a SAF grant doesn't change the DB folder set,
    // so the local-files-health map would stay stale. Kick a recompute on every resume.
    val settingsLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(settingsLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocalFilesFolderHealth()
        }
        settingsLifecycle.addObserver(observer)
        onDispose { settingsLifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                HorizontalDivider()

                SourcesSection(
                    servers = servers,
                    localFilesSource = localFilesSource,
                    localFilesFolders = localFilesFolders,
                    localFilesFolderHealth = localFilesFolderHealth,
                    singletonWebSources = singletonWebSources,
                    serverVersions = serverVersions,
                    libraryItemsBySource = libraryItemsBySource,
                    readaloudSummaries = readaloudSummaries,
                    expandedServers = expandedServers,
                    onNavigateToAddSourcePicker = onNavigateToAddSourcePicker,
                    onNavigateToAddLocalFolder = onNavigateToAddLocalFolder,
                    onOpenReadaloudMatches = { /* Storyteller no longer appears in the Sources list; wire is a no-op. */ },
                    onRemoveServer = viewModel::removeServer,
                    onRemoveLocalFolder = viewModel::removeLocalFolder,
                    onRemoveLocalFilesSource = viewModel::removeLocalFilesSource,
                    onSetLibraryVisible = viewModel::setLibraryVisible,
                    onReorderLibraries = viewModel::setLibraryOrder,
                )
                HorizontalDivider()

                AppearanceSection(
                    appTheme = appTheme,
                    onAppThemeChange = viewModel::setAppTheme,
                )
                HorizontalDivider()

                ReadingSection(
                    globalFormatting = globalFormatting,
                    keepScreenOn = keepScreenOn,
                    volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
                    onOpenPanel = { openPanel = it },
                )
                HorizontalDivider()

                PacingSection(
                    globalFormatting = globalFormatting,
                    onOpenPanel = { openPanel = it },
                )
                HorizontalDivider()

                ListeningSection(onOpenPanel = { openPanel = it })
                HorizontalDivider()

                ReadaloudSection(
                    servers = servers,
                    serverVersions = serverVersions,
                    readaloudSummaries = readaloudSummaries,
                    onOpen = onNavigateToReadaloudSettings,
                )
                HorizontalDivider()

                AnnotationsSyncSection(
                    row = annotationSyncRow,
                    onOpen = onNavigateToAnnotationsSyncSettings,
                )
                HorizontalDivider()

                DiagnosticsSection(
                    crashReports = crashReports,
                    expandedCrashes = expandedCrashes,
                    crashReportFiles = { viewModel.crashReportFiles() },
                    onClearCrashReports = viewModel::clearCrashReports,
                    onNavigateToDebugLogs = onNavigateToDebugLogs,
                )
                HorizontalDivider()

                AppVersionSection(
                    installedVersionName = viewModel.installedVersionName,
                    state = appUpdateState,
                    onCheckForUpdate = viewModel::checkForUpdate,
                    onInstallUpdate = viewModel::downloadAndInstallUpdate,
                )
                HorizontalDivider()
            }
        }
    }

    when (openPanel) {
        SettingsPanel.Formatting -> FormattingSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = viewModel::updateGlobalFormatting,
            onDismiss = { openPanel = null },
        )
        SettingsPanel.Display -> DisplaySettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = viewModel::updateGlobalFormatting,
            onDismiss = { openPanel = null },
        )
        SettingsPanel.Behavior -> BehaviorSettingsPanel(
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
            onVolumeKeyNavigationEnabledChange = viewModel::setVolumeKeyNavigationEnabled,
            invertVolumeKeys = invertVolumeKeys,
            onInvertVolumeKeysChange = viewModel::setInvertVolumeKeys,
            onDismiss = { openPanel = null },
        )
        SettingsPanel.AutoScroll -> AutoScrollSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = viewModel::updateGlobalFormatting,
            onDismiss = { openPanel = null },
        )
        SettingsPanel.Cadence -> CadenceSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = viewModel::updateGlobalFormatting,
            onDismiss = { openPanel = null },
        )
        SettingsPanel.Listening -> ListeningPreferencesPanel(
            defaultSpeed = defaultPlaybackSpeed,
            onDefaultSpeedChange = viewModel::setDefaultPlaybackSpeed,
            skipIntervalSeconds = skipIntervalSeconds,
            onSkipIntervalSecondsChange = viewModel::setSkipIntervalSeconds,
            rewindIntervalSeconds = rewindIntervalSeconds,
            onRewindIntervalSecondsChange = viewModel::setRewindIntervalSeconds,
            rewindOnResumeSeconds = rewindOnResumeSeconds,
            onRewindOnResumeSecondsChange = viewModel::setRewindOnResumeSeconds,
            onDismiss = { openPanel = null },
        )
        null -> {}
    }
}

