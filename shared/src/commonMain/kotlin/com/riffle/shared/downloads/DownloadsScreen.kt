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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
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
                    onRemove = { viewModel.removeDownloadedItem(item) },
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
                    onRemove = { viewModel.removeCachedItem(item) },
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
private fun DownloadRow(item: LocalItemUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(item.item.title, style = TextStyle(fontSize = 14.sp))
            BasicText(
                "${item.mediaTypes.label()} · ${formatBytes(item.sizeBytes)}",
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
