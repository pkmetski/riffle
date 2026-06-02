package com.riffle.app.feature.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.reader.FormattingPanel
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.domain.AudioCachePreferencesStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    onNavigateToReadaloudMatches: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val report = viewModel.lastCrashReport
    val globalFormatting by viewModel.globalFormattingPreferences.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val libraryItems by viewModel.libraryUiItems.collectAsState()
    val storytellerServers by viewModel.storytellerServers.collectAsState()
    val audioCacheCaps by viewModel.audioCacheCaps.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showFormattingPanel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SettingsNavEvent.NavigateToAddServer -> onNavigateToAddServer()
                is SettingsNavEvent.NavigateToReadaloudMatches -> onNavigateToReadaloudMatches(event.serverId)
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
                Text(
                    text = "Servers",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                servers.forEach { server ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.removeServer(server.id)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {},
                    ) {
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
                            headlineContent = { Text(server.serverType.label) },
                            supportingContent = { Text(subtitle) },
                            trailingContent = if (server.isActive) {
                                { Text("Active", style = MaterialTheme.typography.labelSmall) }
                            } else null,
                        )
                    }
                }
                Button(
                    onClick = onNavigateToAddServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Add Server")
                }
                HorizontalDivider()

                if (libraryItems.isNotEmpty()) {
                    val activeServerName = servers.firstOrNull { it.isActive }?.serverType?.label ?: ""
                    Text(
                        text = "Libraries — $activeServerName",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    libraryItems.forEach { item ->
                        ListItem(
                            headlineContent = { Text(item.library.name) },
                            trailingContent = {
                                Switch(
                                    checked = item.isVisible,
                                    onCheckedChange = { visible ->
                                        val serverId = servers.firstOrNull { it.isActive }?.id ?: return@Switch
                                        viewModel.setLibraryVisible(serverId, item.library.id, visible)
                                    },
                                    enabled = item.switchEnabled,
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                }

                if (storytellerServers.isNotEmpty()) {
                    Text(
                        text = "Audio cache",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    storytellerServers.forEach { server ->
                        val capBytes = audioCacheCaps[server.id] ?: AUDIO_CACHE_DEFAULT_CAP_BYTES
                        var menuExpanded by remember(server.id) { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(server.name) },
                            supportingContent = { Text("Maximum auto-cached audio") },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { menuExpanded = true }) {
                                        Text(formatBytesAsGb(capBytes))
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        AUDIO_CACHE_CAP_PRESETS.forEach { preset ->
                                            DropdownMenuItem(
                                                text = { Text(formatBytesAsGb(preset)) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.setAudioCacheCap(server.id, preset)
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                }

                if (storytellerServers.isNotEmpty()) {
                    Text(
                        text = "Readaloud matches",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    storytellerServers.forEach { server ->
                        ListItem(
                            headlineContent = { Text(server.name) },
                            supportingContent = { Text("Review and pair Storyteller readalouds with ABS books") },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { viewModel.openReadaloudMatches(server.id) },
                        )
                    }
                    HorizontalDivider()
                }

                Text(
                    text = "Reading",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { showFormattingPanel = true },
                    headlineContent = { Text("Reading settings") },
                    supportingContent = { Text("Font, theme, spacing, screen wake, volume keys") },
                    trailingContent = {
                        TextButton(onClick = { showFormattingPanel = true }) { Text("Edit") }
                    },
                )
                HorizontalDivider()

                Text(
                    text = "Crash reports",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                if (report == null) {
                    ListItem(
                        headlineContent = { Text("No crashes recorded") },
                        supportingContent = { Text("The app has not crashed since installation") },
                    )
                } else {
                    val timestamp = DateFormat.getDateTimeInstance().format(Date(report.timestampMillis))
                    ListItem(
                        headlineContent = { Text("Last crash") },
                        supportingContent = { Text(timestamp) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("crash report", report.content))
                                        )
                                    }
                                }) {
                                    Text("Copy")
                                }
                                TextButton(onClick = { expanded = !expanded }) {
                                    Text(if (expanded) "Hide" else "Show")
                                }
                            }
                        },
                    )
                    if (expanded) {
                        Text(
                            text = report.content,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showFormattingPanel) {
        FormattingPanel(
            prefs = globalFormatting,
            hasBookOverrides = false,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onReset = {},
            onDismiss = { showFormattingPanel = false },
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
            onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
            invertVolumeKeys = invertVolumeKeys,
            onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            fullScreen = true,
        )
    }
}

private const val GIB: Long = 1024L * 1024 * 1024

private val AUDIO_CACHE_DEFAULT_CAP_BYTES: Long = AudioCachePreferencesStore.DEFAULT_CAP_BYTES

/** Preset audio-cache caps offered in the dropdown: 1 / 2 / 5 / 10 GB. */
private val AUDIO_CACHE_CAP_PRESETS: List<Long> = listOf(1, 2, 5, 10).map { it * GIB }

/** Renders a byte cap as a whole-number "N GB" label. */
private fun formatBytesAsGb(bytes: Long): String {
    val gb = (bytes + GIB / 2) / GIB
    return "$gb GB"
}
