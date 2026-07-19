package com.riffle.app.feature.source.gutenberg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.ui.source.SourceTypeIcon
import com.riffle.core.models.SourceType

/**
 * Zero-config Gutenberg install screen. Presents a brief description of what Riffle is about to
 * add (Project Gutenberg's public catalogue via Gutendex, no credentials, no server URL), a
 * content-source disclosure line, and a single "Add" button. Kicks off
 * [AddGutenbergViewModel.install] and calls [onDone] on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGutenbergScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AddGutenbergViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is AddGutenbergViewModel.State.Success) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceTypeIcon(type = SourceType.GUTENBERG, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Add Project Gutenberg")
                    }
                },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Project Gutenberg",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Browse tens of thousands of free public-domain ebooks. No account is " +
                        "needed — everything is read directly from the public catalogue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Content is provided by gutenberg.org via the gutendex.com API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                when (val s = state) {
                    is AddGutenbergViewModel.State.Idle -> {
                        Button(
                            onClick = viewModel::install,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add source") }
                    }
                    is AddGutenbergViewModel.State.Installing -> {
                        CircularProgressIndicator()
                    }
                    is AddGutenbergViewModel.State.Success -> {
                        // LaunchedEffect above will drive onDone.
                        CircularProgressIndicator()
                    }
                    is AddGutenbergViewModel.State.Error -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = viewModel::install,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Try again") }
                    }
                }
            }
        }
    }
}
