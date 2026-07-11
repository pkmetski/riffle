package com.riffle.app.feature.downloads

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.windowsizeclass.WindowSizeClass
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
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.domain.LibraryItem
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    windowSizeClass: WindowSizeClass,
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
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    SectionHeader(
                        title = "Downloaded",
                        totalLabel = if (uiState.downloadedItems.isNotEmpty()) formatBytes(uiState.downloadedTotalBytes) else null,
                        actionLabel = if (uiState.downloadedItems.isNotEmpty()) "Remove all" else null,
                        onAction = { showRemoveAllDownloadsDialog = true },
                    )
                }
                if (uiState.downloadedItems.isEmpty()) {
                    item {
                        EmptySection("No downloaded books")
                    }
                } else {
                    items(uiState.downloadedItems, key = { it.sourceId + "/" + it.item.id }) { entry ->
                        LocalItemRow(
                            entry = entry,
                            pillColor = PillColor.Downloaded,
                            onClick = { onItemSelected(entry.item) },
                            onRemove = { viewModel.removeDownloadedItem(entry.sourceId, entry.item.id) },
                        )
                    }
                }

                if (uiState.showCachedSection) {
                    item {
                        SectionHeader(
                            title = "Cached",
                            totalLabel = if (uiState.cachedItems.isNotEmpty()) formatBytes(uiState.cachedTotalBytes) else null,
                            actionLabel = if (uiState.cachedItems.isNotEmpty()) "Clear all" else null,
                            onAction = { viewModel.clearAllCached() },
                        )
                    }
                    if (uiState.cachedItems.isEmpty()) {
                        item {
                            EmptySection("No cached books")
                        }
                    } else {
                        items(uiState.cachedItems, key = { it.sourceId + "/" + it.item.id }) { entry ->
                            LocalItemRow(
                                entry = entry,
                                pillColor = PillColor.Cached,
                                onClick = { onItemSelected(entry.item) },
                                onRemove = { viewModel.removeCachedItem(entry.sourceId, entry.item.id) },
                            )
                        }
                    }
                }

                if (uiState.showReadaloudSection) {
                    item {
                        SectionHeader(
                            title = "Readaloud (streaming)",
                            totalLabel = if (uiState.readaloudSidecars.isNotEmpty()) formatBytes(uiState.readaloudSidecarsTotalBytes) else null,
                            actionLabel = if (uiState.readaloudSidecars.isNotEmpty()) "Clear all" else null,
                            onAction = { viewModel.clearAllReadaloudSidecars() },
                        )
                    }
                    if (uiState.readaloudSidecars.isEmpty()) {
                        item {
                            EmptySection("No prepared readalouds")
                        }
                    } else {
                        items(uiState.readaloudSidecars, key = { "sidecar/" + it.sourceId + "/" + it.item.id }) { entry ->
                            LocalItemRow(
                                entry = entry,
                                pillColor = PillColor.Readaloud,
                                onClick = { onItemSelected(entry.item) },
                                onRemove = { viewModel.removeReadaloudSidecar(entry.sourceId, entry.item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, totalLabel: String?, actionLabel: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (totalLabel != null) "$title · $totalLabel" else title,
            style = MaterialTheme.typography.titleMedium,
        )
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

private enum class PillColor { Downloaded, Cached, Readaloud }

@Composable
private fun LocalItemRow(
    entry: LocalItemUi,
    pillColor: PillColor,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val containerColor = when (pillColor) {
        PillColor.Downloaded -> MaterialTheme.colorScheme.primary
        PillColor.Cached -> MaterialTheme.colorScheme.secondary
        PillColor.Readaloud -> MaterialTheme.colorScheme.tertiary
    }
    val contentColor = when (pillColor) {
        PillColor.Downloaded -> MaterialTheme.colorScheme.onPrimary
        PillColor.Cached -> MaterialTheme.colorScheme.onSecondary
        PillColor.Readaloud -> MaterialTheme.colorScheme.onTertiary
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
        Column(modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
        ) {
            Text(text = entry.item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatBytes(entry.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove ${entry.item.title}",
            )
        }
    }
}

/** Renders a byte count as a compact human-readable size (e.g. "312 MB", "1.2 GB"). */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
