package com.riffle.app.feature.settings.sections

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import androidx.core.content.FileProvider
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.crashReportShareSubject
import com.riffle.core.models.CrashReport
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
    SettingsSectionHeader(stringResource(R.string.ui_diagnostics))
    ListItem(
        modifier = Modifier.clickable(onClick = onNavigateToDebugLogs),
        headlineContent = { Text(stringResource(R.string.ui_debug_logs)) },
        supportingContent = { Text(stringResource(R.string.ui_view_share_the_in_app_log_buffer)) },
        trailingContent = { DrillInChevron() },
    )
    HorizontalDivider()
    if (crashReports.isEmpty()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.ui_no_crashes_recorded)) },
            supportingContent = { Text(stringResource(R.string.ui_the_app_has_not_crashed_since_installation)) },
        )
    } else {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.ui_crash_report_count, crashReports.size))
            },
            supportingContent = { Text(stringResource(R.string.ui_newest_first)) },
            trailingContent = {
                Row {
                    TextButton(onClick = {
                        shareCrashReports(context, crashReportFiles())
                    }) { Text(stringResource(R.string.ui_share_all)) }
                    TextButton(onClick = {
                        onClearCrashReports()
                        expandedCrashes.clear()
                    }) { Text(stringResource(R.string.ui_clear)) }
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
                            shareSingleCrashReport(context, timestamp, item.content)
                        }) { Text(stringResource(R.string.ui_share)) }
                        TextButton(onClick = { expandedCrashes[item.id] = !isOpen }) {
                            Text(
                                if (isOpen) {
                                    stringResource(R.string.ui_hide)
                                } else {
                                    stringResource(R.string.ui_show)
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

private fun shareCrashReports(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>(files.size)
    files.forEach { f ->
        uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.ui_crash_reports_subject, files.size))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.ui_share_crash_reports)))
}

private fun shareSingleCrashReport(context: Context, timestamp: String, content: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, crashReportShareSubject(timestamp))
        putExtra(Intent.EXTRA_TEXT, content)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.ui_share_crash_report)))
}
