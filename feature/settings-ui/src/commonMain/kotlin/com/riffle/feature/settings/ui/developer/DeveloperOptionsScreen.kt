package com.riffle.feature.settings.ui.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.core.models.CrashReport
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.settings.ui.DrillInChevron
import com.riffle.feature.settings.ui.crashReportShareSubject
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_back
import com.riffle.feature.settings.ui.generated.resources.ui_clear
import com.riffle.feature.settings.ui.generated.resources.ui_crash_report_count
import com.riffle.feature.settings.ui.generated.resources.ui_crash_reports_subject
import com.riffle.feature.settings.ui.generated.resources.ui_debug_logs
import com.riffle.feature.settings.ui.generated.resources.ui_developer_options
import com.riffle.feature.settings.ui.generated.resources.ui_diagnostics
import com.riffle.feature.settings.ui.generated.resources.ui_github_pat
import com.riffle.feature.settings.ui.generated.resources.ui_github_pat_public_repo_scope
import com.riffle.feature.settings.ui.generated.resources.ui_hide
import com.riffle.feature.settings.ui.generated.resources.ui_newest_first
import com.riffle.feature.settings.ui.generated.resources.ui_no_crashes_recorded
import com.riffle.feature.settings.ui.generated.resources.ui_save
import com.riffle.feature.settings.ui.generated.resources.ui_share
import com.riffle.feature.settings.ui.generated.resources.ui_share_all
import com.riffle.feature.settings.ui.generated.resources.ui_share_crash_report
import com.riffle.feature.settings.ui.generated.resources.ui_share_crash_reports
import com.riffle.feature.settings.ui.generated.resources.ui_show
import com.riffle.feature.settings.ui.generated.resources.ui_the_app_has_not_crashed_since_installation
import com.riffle.feature.settings.ui.generated.resources.ui_used_to_submit_panel_detection_bug_reports_to_the_github_repository
import com.riffle.feature.settings.ui.generated.resources.ui_view_share_the_in_app_log_buffer
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

/**
 * Developer Options screen — GitHub PAT field and diagnostics (debug logs + crash inventory).
 *
 * Crash-report sharing is delegated to the host via hooks so platform-specific FileProvider /
 * UIActivityViewController code stays out of commonMain. The screen resolves localized strings
 * and passes them to the callbacks so the host never needs to call stringResource:
 * - [onShareAllCrashReports]: (subject, chooserTitle) — null hides the button.
 * - [onShareSingleCrashReport]: (report, chooserTitle) — null hides per-report share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onShareAllCrashReports: ((subject: String, chooserTitle: String) -> Unit)? = null,
    onShareSingleCrashReport: ((report: CrashReport, subject: String, chooserTitle: String) -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val githubPat by viewModel.githubPat.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val expandedCrashes = remember { mutableStateMapOf<String, Boolean>() }

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
            GithubPatField(
                currentPat = githubPat,
                onSaveGithubPat = viewModel::onSaveGithubPat,
            )
            HorizontalDivider()
            DiagnosticsSection(
                crashReports = crashReports,
                expandedCrashes = expandedCrashes,
                onClearCrashReports = {
                    viewModel.clearCrashReports()
                    expandedCrashes.clear()
                },
                onNavigateToDebugLogs = onNavigateToDebugLogs,
                onShareAllCrashReports = onShareAllCrashReports,
                onShareSingleCrashReport = onShareSingleCrashReport,
            )
        }
    }
}

@Composable
private fun GithubPatField(
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
            horizontalArrangement = Arrangement.End,
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
internal fun DiagnosticsSection(
    crashReports: List<CrashReport>,
    expandedCrashes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onClearCrashReports: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onShareAllCrashReports: ((subject: String, chooserTitle: String) -> Unit)? = null,
    onShareSingleCrashReport: ((report: CrashReport, subject: String, chooserTitle: String) -> Unit)? = null,
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
        val shareAllSubject = if (onShareAllCrashReports != null) stringResource(Res.string.ui_crash_reports_subject, crashReports.size) else ""
        val shareAllTitle = if (onShareAllCrashReports != null) stringResource(Res.string.ui_share_crash_reports) else ""
        val shareSingleTitle = if (onShareSingleCrashReport != null) stringResource(Res.string.ui_share_crash_report) else ""
        ListItem(
            headlineContent = { Text(stringResource(Res.string.ui_crash_report_count, crashReports.size)) },
            supportingContent = { Text(stringResource(Res.string.ui_newest_first)) },
            trailingContent = {
                Row {
                    if (onShareAllCrashReports != null) {
                        TextButton(onClick = { onShareAllCrashReports(shareAllSubject, shareAllTitle) }) {
                            Text(stringResource(Res.string.ui_share_all))
                        }
                    }
                    TextButton(onClick = onClearCrashReports) {
                        Text(stringResource(Res.string.ui_clear))
                    }
                }
            },
        )
        crashReports.forEach { item ->
            val timestamp = remember(item.timestampMillis) {
                val dt = Instant.fromEpochMilliseconds(item.timestampMillis)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}" +
                    " ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
            }
            val isOpen = expandedCrashes[item.id] == true
            ListItem(
                headlineContent = { Text(timestamp) },
                supportingContent = {
                    Text(
                        item.content.lineSequence().firstOrNull {
                            it.isNotBlank() && it != "STACK_TRACE:"
                        } ?: "",
                    )
                },
                trailingContent = {
                    Row {
                        if (onShareSingleCrashReport != null) {
                            TextButton(onClick = { onShareSingleCrashReport(item, crashReportShareSubject(timestamp), shareSingleTitle) }) {
                                Text(stringResource(Res.string.ui_share))
                            }
                        }
                        TextButton(onClick = { expandedCrashes[item.id] = !isOpen }) {
                            Text(if (isOpen) stringResource(Res.string.ui_hide) else stringResource(Res.string.ui_show))
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
