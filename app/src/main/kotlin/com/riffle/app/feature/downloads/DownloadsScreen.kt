package com.riffle.app.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.R
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.models.LibraryItem
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
    var showCacheSettingsDialog by remember { mutableStateOf(false) }

    if (showRemoveAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_remove_all_downloads)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_this_will_remove_all_downloaded_media_from_your_device)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAllDownloads()
                    showRemoveAllDownloadsDialog = false
                }) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_remove_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDownloadsDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel)) }
            },
        )
    }

    if (showCacheSettingsDialog) {
        CacheSettingsDialog(
            selected = uiState.cacheAutoClear,
            onSelected = viewModel::setCacheAutoClear,
            onDismiss = { showCacheSettingsDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_downloads)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
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
                        title = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_downloaded),
                        totalLabel = if (uiState.downloadedItems.isNotEmpty()) formatBytes(uiState.downloadedTotalBytes) else null,
                        actionLabel = if (uiState.downloadedItems.isNotEmpty()) stringResource(R.string.ui_remove_all) else null,
                        onAction = { showRemoveAllDownloadsDialog = true },
                    )
                }
                if (uiState.downloadedItems.isEmpty()) {
                    item {
                        EmptySection(stringResource(R.string.ui_no_downloaded_media))
                    }
                } else {
                    items(uiState.downloadedItems, key = { "${it.sourceId}_${it.item.id}" }) { entry ->
                        LocalItemRow(
                            entry = entry,
                            pillColor = PillColor.Downloaded,
                            onClick = { onItemSelected(entry.item) },
                            onRemove = { viewModel.removeDownloadedItem(entry) },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cached),
                        totalLabel = if (uiState.cachedItems.isNotEmpty()) formatBytes(uiState.cachedTotalBytes) else null,
                        actionLabel = if (uiState.cachedItems.isNotEmpty()) stringResource(R.string.ui_clear_all) else null,
                        onAction = { viewModel.clearAllCached() },
                    )
                }
                item {
                    CacheSettingsRow(
                        autoClear = uiState.cacheAutoClear,
                        onClick = { showCacheSettingsDialog = true },
                    )
                }
                if (uiState.cachedItems.isEmpty()) {
                    item {
                        EmptySection(stringResource(R.string.ui_no_cached_media))
                    }
                } else {
                    items(uiState.cachedItems, key = { "${it.sourceId}_${it.item.id}" }) { entry ->
                        LocalItemRow(
                            entry = entry,
                            pillColor = PillColor.Cached,
                            onClick = { onItemSelected(entry.item) },
                            onRemove = { viewModel.removeCachedItem(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheSettingsRow(autoClear: ContentCacheAutoClear, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cache_settings))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = autoClear.localizedSummaryLabel(),
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
        title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cache_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cached_book_audiobook_comic_and_readaloud_files_can_be_removed_after_they_have_n),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContentCacheAutoClear.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option.localizedOptionLabel(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_done)) }
        },
    )
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

private enum class PillColor { Downloaded, Cached }

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
    }
    val contentColor = when (pillColor) {
        PillColor.Downloaded -> MaterialTheme.colorScheme.onPrimary
        PillColor.Cached -> MaterialTheme.colorScheme.onSecondary
    }
    val mediaTypeLabel = entry.mediaTypes.localizedDisplayLabel()
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
                    imageVector = entry.mediaTypes.primaryIcon(),
                    contentDescription = mediaTypeLabel,
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
                text = listOf(entry.item.author, mediaTypeLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
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
                contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_remove_named_item, entry.item.title),
            )
        }
    }
}

@Composable
private fun Set<LocalMediaType>.localizedDisplayLabel(): String {
    val labels = mutableListOf<String>()
    for (type in sortedBy { it.displayOrder }) {
        labels += type.localizedLabel()
    }
    return labels.joinToString(" + ")
}

private fun Set<LocalMediaType>.primaryIcon(): ImageVector =
    minByOrNull { it.displayOrder }?.icon ?: Icons.AutoMirrored.Filled.MenuBook

@Composable
private fun LocalMediaType.localizedLabel(): String = when (this) {
    LocalMediaType.Epub -> "EPUB"
    LocalMediaType.Pdf -> "PDF"
    LocalMediaType.Comic -> stringResource(R.string.ui_comic)
    LocalMediaType.Audiobook -> stringResource(R.string.ui_audiobook)
    LocalMediaType.Readaloud -> stringResource(R.string.ui_readaloud)
}

private val LocalMediaType.displayOrder: Int
    get() = when (this) {
        LocalMediaType.Epub -> 0
        LocalMediaType.Pdf -> 1
        LocalMediaType.Comic -> 2
        LocalMediaType.Audiobook -> 3
        LocalMediaType.Readaloud -> 4
    }

private val LocalMediaType.icon: ImageVector
    get() = when (this) {
        LocalMediaType.Epub -> Icons.AutoMirrored.Filled.MenuBook
        LocalMediaType.Pdf -> Icons.Default.PictureAsPdf
        LocalMediaType.Comic -> Icons.Default.GridView
        LocalMediaType.Audiobook -> Icons.Default.GraphicEq
        LocalMediaType.Readaloud -> Icons.Default.GraphicEq
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

@Composable
private fun ContentCacheAutoClear.localizedSummaryLabel(): String =
    days?.let { stringResource(R.string.ui_auto_clear_after_days, it) } ?: stringResource(R.string.ui_auto_clear_off)

@Composable
private fun ContentCacheAutoClear.localizedOptionLabel(): String =
    days?.let { stringResource(R.string.ui_after_days, it) } ?: stringResource(R.string.ui_off)
