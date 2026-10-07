package com.riffle.feature.settings.ui.sections
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.riffle.core.models.CrashReport
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.ui.DrillInChevron
import com.riffle.feature.settings.ui.crashReportShareSubject
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * "Diagnostics" section — Debug logs drill-in + crash-report inventory. Crash reports render
 * inline (rare, few) rather than behind a drill-in so users can share them without an extra tap;
 * each report has its own Share/Show controls.
 */
@Composable
internal fun DiagnosticsSection(
    crashReports: List<CrashReport>,
    expandedCrashes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    crashReportFiles: () -> List<File>,
    onClearCrashReports: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
) {
    val context = LocalContext.current
    // TODO: migrate to Res.string.ui_diagnostics
    SettingsSectionHeader(stringResource(Res.string.ui_diagnostics))
    ListItem(
        modifier = Modifier.clickable(onClick = onNavigateToDebugLogs),
        // TODO: migrate to Res.string.ui_debug_logs
        headlineContent = { Text(stringResource(Res.string.ui_debug_logs)) },
        // TODO: migrate to Res.string.ui_view_share_the_in_app_log_buffer
        supportingContent = { Text(stringResource(Res.string.ui_view_share_the_in_app_log_buffer)) },
        trailingContent = { DrillInChevron() },
    )
    HorizontalDivider()
    val crashReportsSubjectTemplate = stringResource(Res.string.ui_crash_reports_subject)
    val shareCrashReportsTitle = stringResource(Res.string.ui_share_crash_reports)
    val shareCrashReportTitle = stringResource(Res.string.ui_share_crash_report)

    if (crashReports.isEmpty()) {
        ListItem(
            // TODO: migrate to Res.string.ui_no_crashes_recorded
            headlineContent = { Text(stringResource(Res.string.ui_no_crashes_recorded)) },
            // TODO: migrate to Res.string.ui_the_app_has_not_crashed_since_installation
            supportingContent = { Text(stringResource(Res.string.ui_the_app_has_not_crashed_since_installation)) },
        )
    } else {
        ListItem(
            headlineContent = {
                // TODO: migrate to Res.string.ui_crash_report_count
                Text(stringResource(Res.string.ui_crash_report_count, crashReports.size))
            },
            // TODO: migrate to Res.string.ui_newest_first
            supportingContent = { Text(stringResource(Res.string.ui_newest_first)) },
            trailingContent = {
                Row {
                    TextButton(onClick = {
                        shareCrashReports(context, crashReportFiles(), crashReportsSubjectTemplate, shareCrashReportsTitle)
                        // TODO: migrate to Res.string.ui_share_all
                    }) { Text(stringResource(Res.string.ui_share_all)) }
                    TextButton(onClick = {
                        onClearCrashReports()
                        expandedCrashes.clear()
                        // TODO: migrate to Res.string.ui_clear
                    }) { Text(stringResource(Res.string.ui_clear)) }
                }
            },
        )
        crashReports.forEach { item ->
            val timestamp = DateFormat.getDateTimeInstance().format(Date(item.timestampMillis))
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
                        TextButton(onClick = {
                            shareSingleCrashReport(context, timestamp, item.content, shareCrashReportTitle)
                            // TODO: migrate to Res.string.ui_share
                        }) { Text(stringResource(Res.string.ui_share)) }
                        TextButton(onClick = { expandedCrashes[item.id] = !isOpen }) {
                            Text(
                                if (isOpen) {
                                    // TODO: migrate to Res.string.ui_hide
                                    stringResource(Res.string.ui_hide)
                                } else {
                                    // TODO: migrate to Res.string.ui_show
                                    stringResource(Res.string.ui_show)
                                },
                            )
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
}

private fun shareCrashReports(context: Context, files: List<File>, subjectTemplate: String, chooserTitle: String) {
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>(files.size)
    files.forEach { f ->
        uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, String.format(subjectTemplate, files.size))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun shareSingleCrashReport(context: Context, timestamp: String, content: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, crashReportShareSubject(timestamp))
        putExtra(Intent.EXTRA_TEXT, content)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
