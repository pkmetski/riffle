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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import com.riffle.app.R
import com.riffle.app.feature.server.AddSourceBackend
import com.riffle.app.feature.settings.AnnotationSyncBadge
import com.riffle.app.feature.settings.AnnotationSyncRowState
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.disabledListItemColors

/**
 * Full-screen drill-in that hosts every WebDAV annotation-sync setting. Reached from the collapsed
 * WebDAV row on the main Settings screen, whose copy names Komga as the current consumer. Groups:
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
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val row by viewModel.annotationSyncRow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_webdav_annotation_sync_for_komga)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
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
            SettingsSectionHeader(stringResource(R.string.ui_server))
            ListItem(
                modifier = Modifier.clickable {
                    onNavigateToAddSource(AddSourceBackend.Webdav, null)
                },
                leadingContent = { AnnotationSyncBadge(row.badge) },
                headlineContent = { Text(stringResource(R.string.ui_configure_webdav)) },
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

            SettingsSectionHeader(stringResource(R.string.ui_devices))
            val maintenanceEnabled = row.badge != AnnotationSyncRowState.Badge.Local
            ListItem(
                modifier = if (maintenanceEnabled) {
                    Modifier.clickable(onClick = onNavigateToMaintenance)
                } else Modifier,
                headlineContent = { Text(stringResource(R.string.ui_maintenance)) },
                supportingContent = {
                    Text(
                        if (maintenanceEnabled) stringResource(R.string.ui_forget_devices_rename_this_device)
                        else stringResource(R.string.ui_set_up_webdav_first_to_manage_devices),
                    )
                },
                colors = if (maintenanceEnabled) ListItemDefaults.colors() else disabledListItemColors(),
                trailingContent = if (maintenanceEnabled) {
                    { DrillInChevron() }
                } else null,
            )
            HorizontalDivider()
        }
    }
}
