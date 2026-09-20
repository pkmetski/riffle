package com.riffle.shared.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
import com.riffle.feature.downloads.displayLabel
import com.riffle.feature.downloads.formatBytes
import com.riffle.feature.source.ui.CacheSettingsDialog
import com.riffle.feature.source.ui.CacheSettingsRow
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
            // This Column already pads 16.dp horizontally; the shared row's default padding is
            // for Android's unpadded list.
            contentPadding = PaddingValues(vertical = 8.dp),
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
private fun DownloadRow(item: LocalItemUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${item.mediaTypes.displayLabel()} · ${formatBytes(item.sizeBytes)}",
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

