package com.riffle.app.feature.source.chitanka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Zero-config Chitanka install screen. Presents a brief description of what Riffle is about
 * to add (two Bulgarian public libraries, no credentials, no server URL), a content-source
 * disclosure line, and a single "Add" button. Kicks off [AddChitankaViewModel.install] and
 * calls [onDone] on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChitankaScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AddChitankaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is AddChitankaViewModel.State.Success) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Chitanka") },
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
                    "Chitanka",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Browse Bulgarian ebooks and audiobooks from two public digital libraries. " +
                        "No account is needed — everything is read directly from the public sites.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Content is provided by chitanka.info and gramofonche.chitanka.info.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                when (val s = state) {
                    is AddChitankaViewModel.State.Idle -> {
                        Button(
                            onClick = viewModel::install,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add source") }
                    }
                    is AddChitankaViewModel.State.Installing -> {
                        CircularProgressIndicator()
                    }
                    is AddChitankaViewModel.State.Success -> {
                        // LaunchedEffect above will drive onDone.
                        CircularProgressIndicator()
                    }
                    is AddChitankaViewModel.State.Error -> {
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
