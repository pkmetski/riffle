package com.riffle.app.feature.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val report = viewModel.lastCrashReport
    val globalFormatting by viewModel.globalFormattingPreferences.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val libraryItems by viewModel.libraryUiItems.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showFormattingPanel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SettingsNavEvent.NavigateToAddServer -> onNavigateToAddServer()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    ListItem(
                        headlineContent = { Text(server.displayName) },
                        supportingContent = { Text(server.url.value) },
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
                val activeServerName = servers.firstOrNull { it.isActive }?.displayName ?: ""
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

            Text(
                text = "Reading defaults",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Formatting") },
                supportingContent = { Text("Font, theme, spacing & margins") },
                trailingContent = {
                    TextButton(onClick = { showFormattingPanel = true }) { Text("Edit") }
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { viewModel.setKeepScreenOn(!keepScreenOn) },
                headlineContent = { Text("Keep screen on while reading") },
                trailingContent = {
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { viewModel.setVolumeKeyNavigationEnabled(!volumeKeyNavigationEnabled) },
                headlineContent = { Text("Volume key navigation") },
                supportingContent = { Text("Use volume buttons to turn pages while reading") },
                trailingContent = {
                    Switch(
                        checked = volumeKeyNavigationEnabled,
                        onCheckedChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { viewModel.setInvertVolumeKeys(!invertVolumeKeys) },
                headlineContent = { Text("Invert volume keys") },
                supportingContent = { Text("Volume down goes to previous page") },
                trailingContent = {
                    Switch(
                        checked = invertVolumeKeys,
                        onCheckedChange = { viewModel.setInvertVolumeKeys(it) },
                    )
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

    if (showFormattingPanel) {
        FormattingPanel(
            prefs = globalFormatting,
            hasBookOverrides = false,
            onPrefsChange = { viewModel.updateGlobalFormatting(it) },
            onReset = {},
            onDismiss = { showFormattingPanel = false },
        )
    }
}
