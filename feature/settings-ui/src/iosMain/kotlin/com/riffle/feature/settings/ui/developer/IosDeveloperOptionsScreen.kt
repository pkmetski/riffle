package com.riffle.feature.settings.ui.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.core.models.CrashReport
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.settings.ui.DrillInChevron
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_back
import com.riffle.feature.settings.ui.generated.resources.ui_clear
import com.riffle.feature.settings.ui.generated.resources.ui_crash_report_count
import com.riffle.feature.settings.ui.generated.resources.ui_debug_logs
import com.riffle.feature.settings.ui.generated.resources.ui_developer_options
import com.riffle.feature.settings.ui.generated.resources.ui_diagnostics
import com.riffle.feature.settings.ui.generated.resources.ui_github_pat
import com.riffle.feature.settings.ui.generated.resources.ui_github_pat_public_repo_scope
import com.riffle.feature.settings.ui.generated.resources.ui_hide
import com.riffle.feature.settings.ui.generated.resources.ui_newest_first
import com.riffle.feature.settings.ui.generated.resources.ui_no_crashes_recorded
import com.riffle.feature.settings.ui.generated.resources.ui_save
import com.riffle.feature.settings.ui.generated.resources.ui_show
import com.riffle.feature.settings.ui.generated.resources.ui_the_app_has_not_crashed_since_installation
import com.riffle.feature.settings.ui.generated.resources.ui_used_to_submit_panel_detection_bug_reports_to_the_github_repository
import com.riffle.feature.settings.ui.generated.resources.ui_view_share_the_in_app_log_buffer
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * iOS port of the Developer Options screen. Exposes:
 * - GitHub PAT field (for panel-detection bug reports).
 * - Diagnostics section: debug log drill-in + crash report inventory.
 *
 * Unlike the Android version, crash-report sharing uses the system share sheet via UIKit,
 * which is not yet wired here — the Share buttons are omitted until that bridge is in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosDeveloperOptionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val githubPat by viewModel.githubPat.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val expandedCrashes = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_developer_options)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
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
            HorizontalDivider()
            SettingsSectionHeader(stringResource(Res.string.ui_github_pat))
            Text(
                text = stringResource(Res.string.ui_used_to_submit_panel_detection_bug_reports_to_the_github_repository),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            IosGithubPatField(
                currentPat = githubPat,
                onSaveGithubPat = viewModel::onSaveGithubPat,
            )
            HorizontalDivider()
            IosDiagnosticsSection(
                crashReports = crashReports,
                expandedCrashes = expandedCrashes.value,
                onToggleCrashExpanded = { id ->
                    expandedCrashes.value = expandedCrashes.value.toMutableMap().also {
                        it[id] = !(it[id] ?: false)
                    }
                },
                onClearCrashReports = {
                    viewModel.clearCrashReports()
                    expandedCrashes.value = emptyMap()
                },
                onNavigateToDebugLogs = onNavigateToDebugLogs,
            )
        }
    }
}

@Composable
private fun IosGithubPatField(
    currentPat: String,
    onSaveGithubPat: (String) -> Unit,
) {
    var pat by remember { mutableStateOf(currentPat) }
    var isSaved by remember { mutableStateOf(currentPat.isNotEmpty()) }

    LaunchedEffect(currentPat) {
        if (currentPat.isNotEmpty() && pat.isEmpty()) {
            pat = currentPat
            isSaved = true
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = pat,
            onValueChange = {
                pat = it
                isSaved = false
            },
            label = { Text(stringResource(Res.string.ui_github_pat_public_repo_scope)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    onSaveGithubPat(pat)
                    isSaved = true
                },
                enabled = pat.isNotBlank() && !isSaved,
            ) { Text(stringResource(Res.string.ui_save)) }
        }
    }
}

@Composable
private fun IosDiagnosticsSection(
    crashReports: List<CrashReport>,
    expandedCrashes: Map<String, Boolean>,
    onToggleCrashExpanded: (String) -> Unit,
    onClearCrashReports: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
) {
    SettingsSectionHeader(stringResource(Res.string.ui_diagnostics))
    ListItem(
        modifier = Modifier.clickable(onClick = onNavigateToDebugLogs),
        headlineContent = { Text(stringResource(Res.string.ui_debug_logs)) },
        supportingContent = { Text(stringResource(Res.string.ui_view_share_the_in_app_log_buffer)) },
        trailingContent = { DrillInChevron() },
    )
    HorizontalDivider()

    if (crashReports.isEmpty()) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.ui_no_crashes_recorded)) },
            supportingContent = { Text(stringResource(Res.string.ui_the_app_has_not_crashed_since_installation)) },
        )
    } else {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.ui_crash_report_count, crashReports.size)) },
            supportingContent = { Text(stringResource(Res.string.ui_newest_first)) },
            trailingContent = {
                TextButton(onClick = onClearCrashReports) { Text(stringResource(Res.string.ui_clear)) }
            },
        )
        crashReports.forEach { item ->
            val isOpen = expandedCrashes[item.id] == true
            ListItem(
                modifier = Modifier.clickable { onToggleCrashExpanded(item.id) },
                headlineContent = { Text(item.id) },
                supportingContent = {
                    Text(
                        item.content.lineSequence().firstOrNull {
                            it.isNotBlank() && it != "STACK_TRACE:"
                        } ?: "",
                    )
                },
                trailingContent = {
                    TextButton(onClick = { onToggleCrashExpanded(item.id) }) {
                        Text(if (isOpen) stringResource(Res.string.ui_hide) else stringResource(Res.string.ui_show))
                    }
                },
            )
            if (isOpen) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
