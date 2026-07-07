package com.riffle.app.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.domain.PendingSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectLibrariesScreen(
    windowSizeClass: WindowSizeClass,
    pending: PendingSource,
    onNavigateBack: () -> Unit,
    onContinueComplete: () -> Unit,
    viewModel: SelectLibrariesViewModel = hiltViewModel(),
) {
    LaunchedEffect(pending) { viewModel.bind(pending) }
    LaunchedEffect(Unit) { viewModel.navigateHome.collect { onContinueComplete() } }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select libraries") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (viewModel.libraries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This server doesn't expose any book libraries.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onNavigateBack) { Text("Go back") }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = "Choose which libraries to show in Riffle. You can change this later in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(viewModel.libraries, key = { it.id }) { lib ->
                            ListItem(
                                headlineContent = { Text(lib.name) },
                                trailingContent = {
                                    Switch(
                                        checked = lib.id in viewModel.selectedIds,
                                        onCheckedChange = { viewModel.toggle(lib.id) },
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (viewModel.selectedIds.isEmpty()) {
                            Text(
                                "Select at least one library",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = viewModel::onContinue,
                            enabled = viewModel.canContinue,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Continue") }
                    }
                }
            }
        }
    }
}
