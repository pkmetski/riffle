package com.riffle.app.feature.downloads

import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
import com.riffle.feature.downloads.displayOrder
import com.riffle.feature.downloads.formatBytes
import com.riffle.feature.source.ui.CacheSettingsDialog
import com.riffle.feature.source.ui.CacheSettingsRow

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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import com.riffle.app.R
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.models.LibraryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    viewModel: DownloadsViewModel = koinViewModel(),
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

/**
 * Localized mirror of [com.riffle.feature.downloads.label]. Keep the two in step — the untranslated
 * shared one is what iOS renders and what `DownloadsFormattingTest` pins.
 */
@Composable
private fun LocalMediaType.localizedLabel(): String = when (this) {
    LocalMediaType.Epub -> "EPUB"
    LocalMediaType.Pdf -> "PDF"
    LocalMediaType.Comic -> stringResource(R.string.ui_comic)
    LocalMediaType.Audiobook -> stringResource(R.string.ui_audiobook)
    LocalMediaType.Readaloud -> stringResource(R.string.ui_readaloud)
}

private val LocalMediaType.icon: ImageVector
    get() = when (this) {
        LocalMediaType.Epub -> Icons.AutoMirrored.Filled.MenuBook
        LocalMediaType.Pdf -> Icons.Default.PictureAsPdf
        LocalMediaType.Comic -> Icons.Default.GridView
        LocalMediaType.Audiobook -> Icons.Default.GraphicEq
        LocalMediaType.Readaloud -> Icons.Default.GraphicEq
    }

