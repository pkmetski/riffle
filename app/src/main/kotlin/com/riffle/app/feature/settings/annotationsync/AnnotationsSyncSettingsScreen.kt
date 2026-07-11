package com.riffle.app.feature.settings.annotationsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.server.AddSourceBackend
import com.riffle.app.feature.settings.AnnotationSyncBadge
import com.riffle.app.feature.settings.AnnotationSyncRowState
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.disabledListItemColors

/**
 * Full-screen drill-in that hosts every WebDAV annotation-sync setting. Reached from the collapsed
 * "Annotations Sync" row on the main Settings screen. Groups:
 *  - **Server** — the Configure WebDAV row, tinted by [AnnotationSyncRowState] so status stays
 *    visible when the user opens the screen to check "is anything wrong?".
 *  - **Devices** — Maintenance row leading to [AnnotationSyncMaintenanceScreen] for rename /
 *    forget device / etc.
 *
 * When WebDAV is not configured (`Badge.Local`), the Devices row reads as disabled — set-up flow
 * has to happen first so the maintenance screen has something to manage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSyncSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddSource: (AddSourceBackend, String?) -> Unit,
    onNavigateToMaintenance: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val row by viewModel.annotationSyncRow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotations Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            HorizontalDivider()
            SectionHeader("Server")
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable {
                    onNavigateToAddSource(AddSourceBackend.WEBDAV, null)
                },
                leadingContent = { AnnotationSyncBadge(row.badge) },
                headlineContent = { Text("Configure WebDAV") },
                supportingContent = {
                    Text(
                        text = row.sub,
                        color = when (row.subTone) {
                            AnnotationSyncRowState.Tone.Error -> MaterialTheme.colorScheme.error
                            AnnotationSyncRowState.Tone.Pending -> MaterialTheme.colorScheme.tertiary
                            AnnotationSyncRowState.Tone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = { DrillInChevron() },
            )
            HorizontalDivider()

            SectionHeader("Devices")
            HorizontalDivider()
            val maintenanceEnabled = row.badge != AnnotationSyncRowState.Badge.Local
            ListItem(
                modifier = if (maintenanceEnabled) {
                    Modifier.clickable(onClick = onNavigateToMaintenance)
                } else Modifier,
                headlineContent = { Text("Maintenance") },
                supportingContent = {
                    Text(
                        if (maintenanceEnabled) "Forget devices, rename this device"
                        else "Set up WebDAV first to manage devices",
                    )
                },
                colors = if (maintenanceEnabled) ListItemDefaults.colors() else disabledListItemColors(),
                trailingContent = { DrillInChevron() },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
