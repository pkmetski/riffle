package com.riffle.shared.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
import org.koin.compose.koinInject

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<DownloadsViewModel>()
    val state by viewModel.uiState.collectAsState()
    var showCacheSettingsDialog by remember { mutableStateOf(false) }

    if (showCacheSettingsDialog) {
        CacheSettingsDialog(
            selected = state.cacheAutoClear,
            onSelected = { viewModel.setCacheAutoClear(it) },
            onDismiss = { showCacheSettingsDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "← Back",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onBack() }
                .padding(bottom = 12.dp),
        )
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        CacheSettingsRow(
            autoClear = state.cacheAutoClear,
            onClick = { showCacheSettingsDialog = true },
        )

        if (state.downloadedItems.isEmpty() && state.cachedItems.isEmpty()) {
            Text(
                "No downloaded or cached items.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        if (state.downloadedItems.isNotEmpty()) {
            SectionHeader(title = "Downloaded (${formatBytes(state.downloadedTotalBytes)})")
            state.downloadedItems.forEach { item ->
                DownloadRow(item = item, onRemove = { viewModel.removeDownloadedItem(item) })
            }
            Text(
                text = "Remove all downloads",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable { viewModel.removeAllDownloads() }
                    .padding(vertical = 8.dp),
            )
        }

        if (state.cachedItems.isNotEmpty()) {
            SectionHeader(title = "Cached (${formatBytes(state.cachedTotalBytes)})")
            state.cachedItems.forEach { item ->
                DownloadRow(item = item, onRemove = { viewModel.removeCachedItem(item) })
            }
            Text(
                text = "Clear all cached",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable { viewModel.clearAllCached() }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun CacheSettingsRow(autoClear: ContentCacheAutoClear, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClick) {
            Text("Cache settings")
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = autoClear.summaryLabel(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CacheSettingsDialog(
    selected: ContentCacheAutoClear,
    onSelected: (ContentCacheAutoClear) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cache settings") },
        text = {
            Column {
                Text(
                    text = "Cached book, audiobook, comic, and readaloud files can be removed after they have not been opened for this long." +
                        " Downloads are kept.",
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContentCacheAutoClear.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option.optionLabel(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun DownloadRow(item: LocalItemUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${item.mediaTypes.label()} · ${formatBytes(item.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Remove",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { onRemove() },
        )
    }
}

private fun ContentCacheAutoClear.summaryLabel(): String = when (this) {
    ContentCacheAutoClear.Off -> "Auto-clear off"
    else -> "Auto-clear after ${days!!} days"
}

private fun ContentCacheAutoClear.optionLabel(): String = when (this) {
    ContentCacheAutoClear.Off -> "Off"
    else -> "After ${days!!} days"
}

private fun Set<LocalMediaType>.label(): String = joinToString(" + ") { it.label() }

private fun LocalMediaType.label(): String = when (this) {
    LocalMediaType.Epub -> "EPUB"
    LocalMediaType.Pdf -> "PDF"
    LocalMediaType.Comic -> "Comic"
    LocalMediaType.Audiobook -> "Audiobook"
    LocalMediaType.Readaloud -> "Readaloud"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${(bytes.toDouble() / (1024 * 1024 * 1024) * 10).toLong() / 10.0} GB"
}
