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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.riffle.core.domain.StoredMediaType
import org.koin.compose.koinInject

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<DownloadsViewModel>()
    val state by viewModel.uiState.collectAsState()
    var showClearAllDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Back row — matches Android TopAppBar back navigation
        BasicText(
            text = "← Back",
            style = TextStyle(fontSize = 15.sp, color = Color(0xFF1565C0)),
            modifier = Modifier
                .clickable { onBack() }
                .padding(bottom = 12.dp),
        )
        BasicText(
            text = "Downloads",
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (state.isLoading) {
            BasicText("Loading…")
            return@Column
        }

        if (state.downloadedItems.isEmpty() && state.cachedItems.isEmpty()) {
            BasicText("No downloaded or cached items.")
            return@Column
        }

        if (state.downloadedItems.isNotEmpty()) {
            BasicText(
                text = "Downloaded (${formatBytes(state.downloadedTotalBytes)})",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            state.downloadedItems.forEach { item ->
                DownloadRow(
                    item = item,
                    onRemove = { viewModel.removeDownload(item.sourceId, item.itemId) },
                )
            }
            BasicText(
                text = "Remove all downloads",
                style = TextStyle(fontSize = 14.sp),
                modifier = Modifier
                    .clickable { viewModel.removeAllDownloads() }
                    .padding(vertical = 8.dp),
            )
        }

        if (state.cachedItems.isNotEmpty()) {
            BasicText(
                text = "Cached (${formatBytes(state.cachedTotalBytes)})",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            state.cachedItems.forEach { item ->
                DownloadRow(
                    item = item,
                    onRemove = { viewModel.removeCached(item.sourceId, item.itemId) },
                )
            }
            BasicText(
                text = "Clear all cached",
                style = TextStyle(fontSize = 14.sp),
                modifier = Modifier
                    .clickable { viewModel.clearAllCached() }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DownloadRow(item: LocalItemUiState, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(item.itemId, style = TextStyle(fontSize = 14.sp))
            BasicText(
                "${item.mediaType.label()} · ${formatBytes(item.sizeBytes)}",
                style = TextStyle(fontSize = 12.sp),
            )
        }
        Spacer(Modifier.width(8.dp))
        BasicText(
            "Remove",
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.clickable { onRemove() },
        )
    }
}

private fun StoredMediaType.label(): String = when (this) {
    StoredMediaType.Epub -> "EPUB"
    StoredMediaType.Pdf -> "PDF"
    StoredMediaType.Cbz -> "CBZ"
    StoredMediaType.Audiobook -> "Audiobook"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${(bytes.toDouble() / (1024 * 1024 * 1024) * 10).toLong() / 10.0} GB"
}
