package com.riffle.app.feature.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.domain.LibraryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onNavigateBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRemoveAllDownloadsDialog by remember { mutableStateOf(false) }

    if (showRemoveAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsDialog = false },
            title = { Text("Remove all downloads?") },
            text = { Text("This will remove all downloaded books from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAllDownloads()
                    showRemoveAllDownloadsDialog = false
                }) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDownloadsDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                SectionHeader(
                    title = "Downloaded",
                    actionLabel = if (uiState.downloadedItems.isNotEmpty()) "Remove all" else null,
                    onAction = { showRemoveAllDownloadsDialog = true },
                )
            }
            if (uiState.downloadedItems.isEmpty()) {
                item {
                    EmptySection("No downloaded books")
                }
            } else {
                items(uiState.downloadedItems, key = { it.id }) { item ->
                    LocalItemRow(
                        item = item,
                        pillColor = PillColor.Downloaded,
                        onClick = { onItemSelected(item) },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Cached",
                    actionLabel = if (uiState.cachedItems.isNotEmpty()) "Clear all" else null,
                    onAction = { viewModel.clearAllCached() },
                )
            }
            if (uiState.cachedItems.isEmpty()) {
                item {
                    EmptySection("No cached books")
                }
            } else {
                items(uiState.cachedItems, key = { it.id }) { item ->
                    LocalItemRow(
                        item = item,
                        pillColor = PillColor.Cached,
                        onClick = { onItemSelected(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptySection(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class PillColor { Downloaded, Cached }

@Composable
private fun LocalItemRow(
    item: LibraryItem,
    pillColor: PillColor,
    onClick: () -> Unit,
) {
    val containerColor = when (pillColor) {
        PillColor.Downloaded -> MaterialTheme.colorScheme.primary
        PillColor.Cached -> MaterialTheme.colorScheme.secondary
    }
    val contentColor = when (pillColor) {
        PillColor.Downloaded -> MaterialTheme.colorScheme.onPrimary
        PillColor.Cached -> MaterialTheme.colorScheme.onSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
