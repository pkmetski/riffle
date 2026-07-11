package com.riffle.app.feature.source.localfiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.ui.TabletContentWidthContainer

/**
 * Entry point for the Add-Source LocalFiles flow. Auto-launches the SAF folder picker on first
 * frame; on cancel offers a retry / back path; on success surfaces the scan report before
 * handing back to the caller. No form fields — LocalFiles has no credentials to supply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLocalFilesScreen(
    windowSizeClass: WindowSizeClass,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AddLocalFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(PickFolderContract()) { uri ->
        viewModel.onFolderPicked(uri)
    }
    // Autolaunch once per compose entry. `pickerLaunched` guards against relaunching after
    // process-death restore (state is already set to Installing/Success).
    var pickerLaunched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!pickerLaunched && state is AddLocalFilesViewModel.State.Idle) {
            pickerLaunched = true
            launcher.launch(Unit)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add local folder") },
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
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when (val s = state) {
                    AddLocalFilesViewModel.State.Idle -> {
                        // Waiting for the picker to launch — usually invisible.
                        Text("Opening folder picker…")
                    }
                    AddLocalFilesViewModel.State.Cancelled -> CancelledView(
                        onPickAgain = { launcher.launch(Unit) },
                        onNavigateBack = onNavigateBack,
                    )
                    AddLocalFilesViewModel.State.Installing -> InstallingView()
                    is AddLocalFilesViewModel.State.Success -> SuccessView(
                        added = s.report.added,
                        failures = s.report.failures.size,
                        onDone = onDone,
                    )
                    is AddLocalFilesViewModel.State.Error -> ErrorView(
                        message = s.message,
                        onRetry = { launcher.launch(Unit) },
                        onNavigateBack = onNavigateBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text("Scanning your folder…")
    }
}

@Composable
private fun SuccessView(added: Int, failures: Int, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Added $added book${if (added == 1) "" else "s"}.",
            style = MaterialTheme.typography.titleMedium,
        )
        if (failures > 0) {
            Text(
                "$failures item${if (failures == 1) "" else "s"} could not be read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (added == 0) {
            Text(
                "No supported books were found in that folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDone, modifier = Modifier.testTag("AddLocalFiles.Done")) {
            Text("Done")
        }
    }
}

@Composable
private fun CancelledView(onPickAgain: () -> Unit, onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("No folder picked.", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onPickAgain, modifier = Modifier.testTag("AddLocalFiles.PickAgain")) {
            Text("Pick a folder")
        }
        Button(onClick = onNavigateBack) { Text("Cancel") }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Couldn't add that folder.", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("Try again") }
        Button(onClick = onNavigateBack) { Text("Cancel") }
    }
}
