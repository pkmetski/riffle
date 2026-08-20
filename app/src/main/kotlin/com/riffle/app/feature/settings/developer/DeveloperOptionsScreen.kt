package com.riffle.app.feature.settings.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.sections.DiagnosticsSection

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
                title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_developer_options)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
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
            SettingsSectionHeader(stringResource(R.string.ui_github_pat))
            Text(
                text = stringResource(R.string.ui_used_to_submit_panel_detection_bug_reports_to_the_github_repository),
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
                crashReportFiles = viewModel::crashReportFiles,
                onClearCrashReports = viewModel::clearCrashReports,
                onNavigateToDebugLogs = onNavigateToDebugLogs,
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
            onValueChange = { pat = it; isSaved = false },
            label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_github_pat_public_repo_scope)) },
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
                onClick = { onSaveGithubPat(pat); isSaved = true },
                enabled = pat.isNotBlank() && !isSaved,
            ) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_save)) }
        }
    }
}
