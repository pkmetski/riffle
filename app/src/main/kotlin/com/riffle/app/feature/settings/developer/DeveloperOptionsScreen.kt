package com.riffle.app.feature.settings.developer

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.sections.DiagnosticsSection
import com.riffle.app.feature.settings.sections.GithubPatField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val githubPat by viewModel.githubPat.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val expandedCrashes = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer options") },
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
            HorizontalDivider()
            SettingsSectionHeader("GitHub PAT")
            Text(
                text = "Used to submit panel detection bug reports to the GitHub repository.",
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
                crashReportFiles = { viewModel.crashReportFiles() },
                onClearCrashReports = viewModel::clearCrashReports,
                onNavigateToDebugLogs = onNavigateToDebugLogs,
            )
        }
    }
}
