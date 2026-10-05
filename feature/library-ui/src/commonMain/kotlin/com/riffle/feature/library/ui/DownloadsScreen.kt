package com.riffle.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.TabletContentWidthContainer
import com.riffle.feature.designsystem.TestTags
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.feature.downloads.DownloadsUiState
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
import com.riffle.feature.downloads.displayOrder
import com.riffle.feature.downloads.formatBytes
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_audiobook
import com.riffle.feature.library.ui.generated.resources.ui_back
import com.riffle.feature.library.ui.generated.resources.ui_cancel
import com.riffle.feature.library.ui.generated.resources.ui_clear_all
import com.riffle.feature.library.ui.generated.resources.ui_comic
import com.riffle.feature.library.ui.generated.resources.ui_downloads
import com.riffle.feature.library.ui.generated.resources.ui_no_cached_media
import com.riffle.feature.library.ui.generated.resources.ui_no_downloaded_media
import com.riffle.feature.library.ui.generated.resources.ui_readaloud
import com.riffle.feature.library.ui.generated.resources.ui_remove_all
import com.riffle.feature.library.ui.generated.resources.ui_clear_all_cached
import com.riffle.feature.library.ui.generated.resources.ui_remove_all_downloads
import com.riffle.feature.library.ui.generated.resources.ui_remove_named_item
import com.riffle.feature.library.ui.generated.resources.ui_this_will_clear_all_cached_media_from_your_device
import com.riffle.feature.library.ui.generated.resources.ui_this_will_remove_all_downloaded_media_from_your_device
import com.riffle.feature.designsystem.generated.resources.Res as DsRes
import com.riffle.feature.designsystem.generated.resources.ui_cached
import com.riffle.feature.designsystem.generated.resources.ui_downloaded
import com.riffle.feature.source.ui.CacheSettingsDialog
import com.riffle.feature.source.ui.CacheSettingsRow
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    isExpandedWidth: Boolean = false,
    onNavigateBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    viewModel: DownloadsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    DownloadsContent(
        state = uiState,
        isExpandedWidth = isExpandedWidth,
        onBack = onNavigateBack,
        onSetCacheAutoClear = viewModel::setCacheAutoClear,
        onRemoveDownloadedItem = viewModel::removeDownloadedItem,
        onRemoveCachedItem = viewModel::removeCachedItem,
        onItemSelected = onItemSelected,
        onRemoveAllDownloads = viewModel::removeAllDownloads,
        onClearAllCached = viewModel::clearAllCached,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsContent(
    state: DownloadsUiState,
    isExpandedWidth: Boolean = false,
    onBack: () -> Unit,
    onSetCacheAutoClear: (ContentCacheAutoClear) -> Unit,
    onRemoveDownloadedItem: (LocalItemUi) -> Unit,
    onRemoveCachedItem: (LocalItemUi) -> Unit,
    onItemSelected: (LibraryItem) -> Unit = {},
    onRemoveAllDownloads: () -> Unit,
    onClearAllCached: () -> Unit,
) {
    var showRemoveAllDownloadsDialog by remember { mutableStateOf(false) }
    var showClearAllCachedDialog by remember { mutableStateOf(false) }
    var showCacheSettingsDialog by remember { mutableStateOf(false) }

    if (showRemoveAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsDialog = false },
            title = { Text(stringResource(Res.string.ui_remove_all_downloads)) },
            text = { Text(stringResource(Res.string.ui_this_will_remove_all_downloaded_media_from_your_device)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveAllDownloads()
                        showRemoveAllDownloadsDialog = false
                    },
                    modifier = Modifier.testTag(TestTags.DOWNLOADS_CONFIRM_REMOVE_ALL),
                ) { Text(stringResource(Res.string.ui_remove_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDownloadsDialog = false }) {
                    Text(stringResource(Res.string.ui_cancel))
                }
            },
        )
    }

    if (showClearAllCachedDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllCachedDialog = false },
            title = { Text(stringResource(Res.string.ui_clear_all_cached)) },
            text = { Text(stringResource(Res.string.ui_this_will_clear_all_cached_media_from_your_device)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAllCached()
                        showClearAllCachedDialog = false
                    },
                    modifier = Modifier.testTag(TestTags.DOWNLOADS_CONFIRM_CLEAR_CACHED),
                ) { Text(stringResource(Res.string.ui_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllCachedDialog = false }) {
                    Text(stringResource(Res.string.ui_cancel))
                }
            },
        )
    }

    if (showCacheSettingsDialog) {
        CacheSettingsDialog(
            selected = state.cacheAutoClear,
            onSelected = onSetCacheAutoClear,
            onDismiss = { showCacheSettingsDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_downloads)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(TestTags.DOWNLOADS_BACK),
                    ) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            isExpandedWidth = isExpandedWidth,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SectionHeader(
                        title = stringResource(DsRes.string.ui_downloaded),
                        totalLabel = if (state.downloadedItems.isNotEmpty()) formatBytes(state.downloadedTotalBytes) else null,
                        actionLabel = if (state.downloadedItems.isNotEmpty()) stringResource(Res.string.ui_remove_all) else null,
                        onAction = { showRemoveAllDownloadsDialog = true },
                        actionTag = TestTags.DOWNLOADS_REMOVE_ALL,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.downloadedItems.isEmpty()) {
                    item {
                        DownloadsEmptySection(stringResource(Res.string.ui_no_downloaded_media))
                    }
                } else {
                    items(state.downloadedItems, key = { "${it.sourceId}_${it.item.id}" }) { entry ->
                        LocalItemRow(
                            entry = entry,
                            pillColor = PillColor.Downloaded,
                            onClick = { onItemSelected(entry.item) },
                            onRemove = { onRemoveDownloadedItem(entry) },
                        )
                    }
                }
                item {
                    SectionHeader(
                        title = stringResource(DsRes.string.ui_cached),
                        totalLabel = if (state.cachedItems.isNotEmpty()) formatBytes(state.cachedTotalBytes) else null,
                        actionLabel = if (state.cachedItems.isNotEmpty()) stringResource(Res.string.ui_clear_all) else null,
                        onAction = { showClearAllCachedDialog = true },
                        actionTag = TestTags.DOWNLOADS_CLEAR_CACHED,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    CacheSettingsRow(
                        autoClear = state.cacheAutoClear,
                        onClick = { showCacheSettingsDialog = true },
                    )
                }
                if (state.cachedItems.isEmpty()) {
                    item {
                        DownloadsEmptySection(stringResource(Res.string.ui_no_cached_media))
                    }
                } else {
                    items(state.cachedItems, key = { "${it.sourceId}_${it.item.id}" }) { entry ->
                        LocalItemRow(
                            entry = entry,
                            pillColor = PillColor.Cached,
                            onClick = { onItemSelected(entry.item) },
                            onRemove = { onRemoveCachedItem(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsEmptySection(message: String) {
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
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
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
                imageVector = LibraryUiGlyphs.Delete,
                contentDescription = stringResource(Res.string.ui_remove_named_item, entry.item.title),
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
    minByOrNull { it.displayOrder }?.icon ?: LibraryUiGlyphs.MenuBook

@Composable
private fun LocalMediaType.localizedLabel(): String = when (this) {
    LocalMediaType.Epub -> "EPUB"
    LocalMediaType.Pdf -> "PDF"
    LocalMediaType.Comic -> stringResource(Res.string.ui_comic)
    LocalMediaType.Audiobook -> stringResource(Res.string.ui_audiobook)
    LocalMediaType.Readaloud -> stringResource(Res.string.ui_readaloud)
}

private val LocalMediaType.icon: ImageVector
    get() = when (this) {
        LocalMediaType.Epub -> LibraryUiGlyphs.MenuBook
        LocalMediaType.Pdf -> LibraryUiGlyphs.PictureAsPdf
        LocalMediaType.Comic -> LibraryUiGlyphs.GridView
        LocalMediaType.Audiobook -> LibraryUiGlyphs.GraphicEq
        LocalMediaType.Readaloud -> LibraryUiGlyphs.GraphicEq
    }
