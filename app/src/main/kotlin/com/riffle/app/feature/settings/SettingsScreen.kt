package com.riffle.app.feature.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.reader.behaviorSummary
import com.riffle.app.feature.reader.displaySummary
import com.riffle.app.feature.reader.formattingSummary
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.feature.server.AddServerBackend
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.Source
import com.riffle.core.domain.ServerType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onNavigateToAddServer: (backend: AddServerBackend, editId: String?) -> Unit,
    onNavigateToReadaloudMatches: (String) -> Unit = {},
    onNavigateToAnnotationSyncMaintenance: () -> Unit = {},
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
    val serverVersions by viewModel.serverVersions.collectAsState()
    val libraryItemsByServer by viewModel.libraryUiItemsByServer.collectAsState()
    val readaloudSummaries by viewModel.readaloudSummaries.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val readaloudPreferences by viewModel.readaloudPreferences.collectAsState()
    val defaultPlaybackSpeed by viewModel.defaultPlaybackSpeed.collectAsState()
    val skipIntervalSeconds by viewModel.skipIntervalSeconds.collectAsState()
    val rewindIntervalSeconds by viewModel.rewindIntervalSeconds.collectAsState()
    val rewindOnResumeSeconds by viewModel.rewindOnResumeSeconds.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val expandedServers = remember { mutableStateMapOf<String, Boolean>() }
    val expandedCrashes = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current
    var showFormattingPanel by remember { mutableStateOf(false) }
    var showDisplayPanel by remember { mutableStateOf(false) }
    var showBehaviorPanel by remember { mutableStateOf(false) }
    var showAutoScrollPanel by remember { mutableStateOf(false) }
    var showCadencePanel by remember { mutableStateOf(false) }
    var showListeningPanel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SettingsNavEvent.NavigateToAddServer -> onNavigateToAddServer(AddServerBackend.AUDIOBOOKSHELF, null)
                is SettingsNavEvent.NavigateToReadaloudMatches -> onNavigateToReadaloudMatches(event.sourceId)
            }
        }
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

                Text(
                    text = "Audiobookshelf servers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                servers.filter { it.serverType == ServerType.AUDIOBOOKSHELF }.forEach { server ->
                    ServerRow(
                        server = server,
                        isExpanded = expandedServers[server.id] == true,
                        onToggleExpanded = {
                            expandedServers[server.id] = expandedServers[server.id] != true
                        },
                        onRemove = { viewModel.removeServer(server.id) },
                        serverVersion = serverVersions[server.id],
                        libraryItems = libraryItemsByServer[server.id].orEmpty(),
                        summary = readaloudSummaries[server.id],
                        onSetLibraryVisible = { libraryId, visible ->
                            viewModel.setLibraryVisible(server.id, libraryId, visible)
                        },
                        onReorderLibraries = { orderedIds ->
                            viewModel.setLibraryOrder(server.id, orderedIds)
                        },
                        onOpenReadaloudMatches = { viewModel.openReadaloudMatches(server.id) },
                    )
                }
                Button(
                    onClick = { onNavigateToAddServer(AddServerBackend.AUDIOBOOKSHELF, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Add server")
                }
                HorizontalDivider()

                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                AppThemeRow(
                    appTheme = appTheme,
                    onAppThemeChange = { viewModel.setAppTheme(it) },
                )
                HorizontalDivider()

                Text(
                    text = "Reading",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { showFormattingPanel = true },
                    headlineContent = { Text("Formatting") },
                    supportingContent = { Text(formattingSummary(globalFormatting)) },
                    trailingContent = {
                        TextButton(onClick = { showFormattingPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showDisplayPanel = true },
                    headlineContent = { Text("Display") },
                    supportingContent = { Text(displaySummary(globalFormatting)) },
                    trailingContent = {
                        TextButton(onClick = { showDisplayPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showBehaviorPanel = true },
                    headlineContent = { Text("Behavior") },
                    supportingContent = { Text(behaviorSummary(keepScreenOn, volumeKeyNavigationEnabled)) },
                    trailingContent = {
                        TextButton(onClick = { showBehaviorPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showAutoScrollPanel = true },
                    leadingContent = {
                        com.riffle.app.feature.reader.autoscroll.AutoScrollToggleIcon(
                            isRunning = false,
                            onClick = { showAutoScrollPanel = true },
                        )
                    },
                    headlineContent = { Text("Auto-Scroll") },
                    supportingContent = {
                        Text(
                            if (globalFormatting.showAutoScroll)
                                "Hands-free scroll — ${globalFormatting.autoScrollWpm} wpm"
                            else "Off",
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showAutoScrollPanel = true }) { Text("Edit") }
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { showCadencePanel = true },
                    leadingContent = {
                        androidx.compose.material3.IconButton(onClick = { showCadencePanel = true }) {
                            com.riffle.app.feature.reader.cadence.CadenceHeroIcon(size = 24.dp)
                        }
                    },
                    headlineContent = { Text("Cadence") },
                    supportingContent = {
                        Text(
                            if (globalFormatting.showCadence)
                                "Sentence highlight — ${globalFormatting.cadenceWpm} wpm"
                            else "Off",
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showCadencePanel = true }) { Text("Edit") }
                    },
                )
                HorizontalDivider()

                Text(
                    text = "Listening",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { showListeningPanel = true },
                    headlineContent = { Text("Preferences") },
                    supportingContent = { Text("Speed, skip interval, rewind on resume") },
                    trailingContent = {
                        TextButton(onClick = { showListeningPanel = true }) { Text("Edit") }
                    },
                )
                HorizontalDivider()

                Text(
                    text = "Readaloud",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                val storytellerServers = servers.filter { it.serverType == ServerType.STORYTELLER }
                if (storytellerServers.isEmpty()) {
                    ListItem(
                        modifier = Modifier.clickable { onNavigateToAddServer(AddServerBackend.STORYTELLER, null) },
                        leadingContent = { StorytellerBadge(configured = false) },
                        headlineContent = { Text("Configure Storyteller") },
                        supportingContent = { Text("Not configured") },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        },
                    )
                } else {
                    storytellerServers.forEach { server ->
                        val username = server.username.takeIf { it.isNotEmpty() }
                        val version = serverVersions[server.id]
                        val subtitle = buildString {
                            if (username != null) {
                                append(username)
                                append(" · ")
                            }
                            append(server.url.value)
                            if (version != null) {
                                append(" · v")
                                append(version)
                            }
                        }
                        ListItem(
                            modifier = Modifier.clickable {
                                onNavigateToAddServer(AddServerBackend.STORYTELLER, server.id)
                            },
                            leadingContent = { StorytellerBadge(configured = true) },
                            headlineContent = { Text("Storyteller") },
                            supportingContent = { Text(subtitle) },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
                val storytellerActive = storytellerServers.firstOrNull()
                ListItem(
                    modifier = if (storytellerActive != null) {
                        Modifier.clickable { viewModel.openReadaloudMatches(storytellerActive.id) }
                    } else {
                        Modifier
                    },
                    headlineContent = { Text("Review readaloud matches") },
                    supportingContent = {
                        val s = storytellerActive?.let { readaloudSummaries[it.id] }
                        if (s != null) {
                            Text(
                                "${s.unmatchedCount} unmatched · " +
                                    "${s.suggestedCount} suggested · " +
                                    "${s.partiallyMatchedCount} partially matched · " +
                                    "${s.matchedCount} matched",
                            )
                        } else {
                            Text("Configure Storyteller first to review matches")
                        }
                    },
                    colors = if (storytellerActive != null) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
                val highlightEnabled = storytellerServers.isNotEmpty()
                ListItem(
                    headlineContent = { Text("Sentence highlight") },
                    colors = if (highlightEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HighlightColor.entries.forEach { color ->
                                val isSelected = readaloudPreferences.highlightColor == color
                                // The swatch renders the exact `argb` from [HighlightColor] — same
                                // pixel value that lands in the reader. Do NOT compose additional
                                // alpha in the enabled case; the picker must be a faithful preview.
                                // Disabled: drop to the Material standard 0.38 (absolute, not
                                // multiplicative against the palette's baked-in 0x80 — that would
                                // land at ~0.19 and read as near-invisible next to the sibling
                                // disabled `headlineColor` above, which uses `alpha = 0.38f` too).
                                val base = Color(color.argb.toLong() and 0xFFFFFFFFL)
                                val swatchColor = if (highlightEnabled) base else base.copy(alpha = 0.38f)
                                // Selected swatch reads clearly in both themes: an offset ring
                                // (onSurface ring at the outer edge, separated from the swatch by a
                                // transparent gap) plus a centred checkmark. The swatch keeps a
                                // constant 28dp size whether or not it's selected — the 4dp padding
                                // is always reserved, so the row layout doesn't shift on selection.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (highlightEnabled) {
                                                Modifier.clickable { viewModel.updateHighlightColor(color) }
                                            } else Modifier
                                        )
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        )
                                        .padding(4.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .semantics {
                                            contentDescription = color.name.lowercase()
                                                .replaceFirstChar { it.uppercase() } + " highlight" +
                                                if (isSelected) ", selected" else ""
                                        },
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xDD000000),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()

                Text(
                    text = "Annotations Sync",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                val annotationSyncRow by viewModel.annotationSyncRow.collectAsState()
                ListItem(
                    modifier = Modifier.clickable {
                        onNavigateToAddServer(AddServerBackend.WEBDAV, null)
                    },
                    leadingContent = { AnnotationSyncBadge(annotationSyncRow.badge) },
                    headlineContent = { Text("Configure WebDAV") },
                    supportingContent = {
                        Text(
                            text = annotationSyncRow.sub,
                            color = when (annotationSyncRow.subTone) {
                                AnnotationSyncRowState.Tone.Error -> MaterialTheme.colorScheme.error
                                AnnotationSyncRowState.Tone.Pending -> MaterialTheme.colorScheme.tertiary
                                AnnotationSyncRowState.Tone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
                val maintenanceEnabled = annotationSyncRow.badge != AnnotationSyncRowState.Badge.Local
                ListItem(
                    modifier = if (maintenanceEnabled) {
                        Modifier.clickable { onNavigateToAnnotationSyncMaintenance() }
                    } else {
                        Modifier
                    },
                    headlineContent = { Text("Maintenance") },
                    supportingContent = {
                        Text(
                            if (maintenanceEnabled) "Forget devices, rename this device"
                            else "Set up WebDAV first to manage devices",
                        )
                    },
                    colors = if (maintenanceEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
                HorizontalDivider()

                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { onNavigateToDebugLogs() },
                    headlineContent = { Text("Debug logs") },
                    supportingContent = { Text("View / share the in-app log buffer") },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
                HorizontalDivider()
                if (crashReports.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("No crashes recorded") },
                        supportingContent = { Text("The app has not crashed since installation") },
                    )
                } else {
                    // Bulk affordances: "Share all" zips nothing — it just attaches each .txt
                    // file via FileProvider so the user can pick an email / messaging app from
                    // the share sheet. "Clear" deletes the on-disk archive.
                    ListItem(
                        headlineContent = { Text("${crashReports.size} crash report${if (crashReports.size == 1) "" else "s"}") },
                        supportingContent = { Text("Newest first") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    val files = viewModel.crashReportFiles()
                                    if (files.isEmpty()) return@TextButton
                                    val uris = ArrayList<Uri>(files.size)
                                    files.forEach { f ->
                                        uris += FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            f,
                                        )
                                    }
                                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "text/plain"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        putExtra(Intent.EXTRA_SUBJECT, "Riffle crash reports (${files.size})")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share crash reports"))
                                }) {
                                    Text("Share")
                                }
                                TextButton(onClick = {
                                    viewModel.clearCrashReports()
                                    expandedCrashes.clear()
                                }) {
                                    Text("Clear")
                                }
                            }
                        },
                    )
                    crashReports.forEach { item ->
                        val timestamp = DateFormat.getDateTimeInstance().format(Date(item.timestampMillis))
                        val isOpen = expandedCrashes[item.id] == true
                        ListItem(
                            headlineContent = { Text(timestamp) },
                            supportingContent = { Text(item.content.lineSequence().firstOrNull { it.isNotBlank() && it != "STACK_TRACE:" } ?: "") },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("crash report", item.content))
                                            )
                                        }
                                    }) {
                                        Text("Copy")
                                    }
                                    TextButton(onClick = { expandedCrashes[item.id] = !isOpen }) {
                                        Text(if (isOpen) "Hide" else "Show")
                                    }
                                }
                            },
                        )
                        if (isOpen) {
                            Text(
                                text = item.content,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
                HorizontalDivider()

                Text(
                    text = "App version",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                AppVersionRow(
                    installedVersionName = viewModel.installedVersionName,
                    state = appUpdateState,
                    onCheckForUpdate = { viewModel.checkForUpdate() },
                    onInstallUpdate = { viewModel.downloadAndInstallUpdate() },
                )
                HorizontalDivider()
            }
        }
    }

    if (showFormattingPanel) {
        FormattingSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showFormattingPanel = false },
        )
    }

    if (showDisplayPanel) {
        DisplaySettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showDisplayPanel = false },
        )
    }

    if (showBehaviorPanel) {
        BehaviorSettingsPanel(
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
            onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
            invertVolumeKeys = invertVolumeKeys,
            onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            onDismiss = { showBehaviorPanel = false },
        )
    }

    if (showAutoScrollPanel) {
        AutoScrollSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showAutoScrollPanel = false },
        )
    }

    if (showCadencePanel) {
        CadenceSettingsPanel(
            prefs = globalFormatting,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onDismiss = { showCadencePanel = false },
        )
    }

    if (showListeningPanel) {
        ListeningPreferencesPanel(
            defaultSpeed = defaultPlaybackSpeed,
            onDefaultSpeedChange = { viewModel.setDefaultPlaybackSpeed(it) },
            skipIntervalSeconds = skipIntervalSeconds,
            onSkipIntervalSecondsChange = { viewModel.setSkipIntervalSeconds(it) },
            rewindIntervalSeconds = rewindIntervalSeconds,
            onRewindIntervalSecondsChange = { viewModel.setRewindIntervalSeconds(it) },
            rewindOnResumeSeconds = rewindOnResumeSeconds,
            onRewindOnResumeSecondsChange = { viewModel.setRewindOnResumeSeconds(it) },
            onDismiss = { showListeningPanel = false },
        )
    }
}

/**
 * App-chrome theme selector (Light / Dark / System). Independent of the reader's content theme —
 * this drives the Material color scheme of everything outside the reading surface.
 */
@Composable
private fun AppThemeRow(
    appTheme: AppTheme,
    onAppThemeChange: (AppTheme) -> Unit,
) {
    val options = listOf(
        AppTheme.Light to "Light",
        AppTheme.Dark to "Dark",
        AppTheme.System to "System",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (theme, label) ->
            SegmentedButton(
                selected = theme == appTheme,
                onClick = { onAppThemeChange(theme) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

/**
 * One server entry in the Settings list: swipe-to-remove wrapper, the collapsed header row
 * (chevron + type label + username/url/version + Active pill), and the expandable body that
 * reveals ABS library toggles or the Storyteller readaloud-matches summary.
 */
@Composable
private fun ServerRow(
    server: Source,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    serverVersion: String?,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else false
        }
    )
    SwipeToDismissBox(state = dismissState, backgroundContent = {}) {
        Column {
            val username = server.username.takeIf { it.isNotEmpty() }
            val subtitle = buildString {
                if (username != null) {
                    append(username)
                    append(" · ")
                }
                append(server.url.value)
                if (serverVersion != null) {
                    append(" · v")
                    append(serverVersion)
                }
            }
            val chevronRotation by animateFloatAsState(
                targetValue = if (isExpanded) 90f else 0f,
                label = "chevron",
            )
            ListItem(
                modifier = Modifier.clickable { onToggleExpanded() },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                headlineContent = { Text(server.serverType.label) },
                supportingContent = { Text(subtitle) },
                trailingContent = if (server.isActive) {
                    { Text("Active", style = MaterialTheme.typography.labelSmall) }
                } else null,
            )
            AnimatedVisibility(visible = isExpanded) {
                ServerSettingsExpansion(
                    server = server,
                    libraryItems = libraryItems,
                    summary = summary,
                    onSetLibraryVisible = onSetLibraryVisible,
                    onReorderLibraries = onReorderLibraries,
                    onOpenReadaloudMatches = onOpenReadaloudMatches,
                )
            }
        }
    }
}

/**
 * The settings revealed when a server row is expanded: ABS servers show their library visibility
 * switches; Storyteller servers show a readaloud-matches summary that opens the full matches screen.
 */
@Composable
internal fun ServerSettingsExpansion(
    server: Source,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val transparentColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        when (server.serverType) {
            ServerType.AUDIOBOOKSHELF -> {
                ExpansionHeader("Enabled libraries")
                if (libraryItems.isEmpty()) {
                    ExpansionNote("No libraries found.")
                } else {
                    ReorderableLibraryList(
                        items = libraryItems,
                        onSetLibraryVisible = onSetLibraryVisible,
                        onReorder = onReorderLibraries,
                    )
                }
            }
            ServerType.STORYTELLER -> {
                ExpansionHeader("Readaloud matches")
                val counts = summary ?: ReadaloudMatchSummary(0, 0, 0, 0)
                ListItem(
                    colors = transparentColors,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .clickable { onOpenReadaloudMatches() },
                    headlineContent = { Text("Review & match readalouds") },
                    supportingContent = {
                        Text(
                            "${counts.unmatchedCount} unmatched · " +
                                "${counts.suggestedCount} suggested · " +
                                "${counts.partiallyMatchedCount} partially matched · " +
                                "${counts.matchedCount} matched",
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                )
            }
        }
    }
}

/**
 * Inline "App version" row: shows the installed version and the manual update-check status, advancing
 * through check → (up-to-date | update available → download % → install) as the user taps.
 */
@Composable
private fun AppVersionRow(
    installedVersionName: String,
    state: AppUpdateUiState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val supporting = when (state) {
        is AppUpdateUiState.Idle -> "Installed: v$installedVersionName"
        is AppUpdateUiState.Checking -> "Checking for updates…"
        is AppUpdateUiState.UpToDate -> "Installed: v$installedVersionName · Up to date"
        is AppUpdateUiState.UpdateAvailable -> "Update available: v${state.versionName}"
        is AppUpdateUiState.Downloading -> "Downloading update… ${state.percent}%"
        is AppUpdateUiState.Installing -> "Starting installer…"
        is AppUpdateUiState.Failed -> "Update check failed: ${state.message}"
    }
    ListItem(
        headlineContent = { Text("Riffle") },
        supportingContent = { Text(supporting) },
        trailingContent = {
            when (state) {
                is AppUpdateUiState.Checking ->
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                is AppUpdateUiState.Downloading ->
                    CircularProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                is AppUpdateUiState.Installing -> {}
                is AppUpdateUiState.UpdateAvailable ->
                    Button(onClick = onInstallUpdate) { Text("Update") }
                is AppUpdateUiState.Failed ->
                    TextButton(onClick = onCheckForUpdate) { Text("Retry") }
                else ->
                    TextButton(onClick = onCheckForUpdate) { Text("Check for updates") }
            }
        },
    )
}

@Composable
private fun ExpansionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ExpansionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun StorytellerBadge(configured: Boolean) {
    val (bg, fg) = if (configured) {
        Color(0x336CD591L) to Color(0xFF6CD591L)
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Headphones,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AnnotationSyncBadge(badge: AnnotationSyncRowState.Badge) {
    val (bg, fg, glyph) = when (badge) {
        AnnotationSyncRowState.Badge.Local -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Outlined.CloudOff,
        )
        AnnotationSyncRowState.Badge.Synced -> Triple(
            Color(0x336CD591L), // success bg, ~20% alpha
            Color(0xFF6CD591L),
            Icons.Default.CheckCircle,
        )
        AnnotationSyncRowState.Badge.Pending -> Triple(
            Color(0x33F5B94CL),
            Color(0xFFF5B94CL),
            Icons.Default.Schedule,
        )
        AnnotationSyncRowState.Badge.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Icons.Default.Warning,
        )
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

