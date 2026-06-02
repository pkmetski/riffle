package com.riffle.app.feature.library

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.core.domain.LibraryItem
import kotlinx.coroutines.launch

const val LIBRARY_ITEM_DETAIL_LEFT_PANE_TAG = "library_item_detail_left_pane"
const val LIBRARY_ITEM_DETAIL_RIGHT_PANE_TAG = "library_item_detail_right_pane"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemDetailScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onReadItem: (LibraryItem) -> Unit,
    viewModel: LibraryItemDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val audioDownloadState by viewModel.audioDownloadState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState is LibraryItemDetailUiState.Ready) {
                        Text(
                            text = (uiState as LibraryItemDetailUiState.Ready).item.title,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val state = uiState) {
            is LibraryItemDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is LibraryItemDetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Could not load book details.")
                }
            }

            is LibraryItemDetailUiState.Ready -> {
                val onRemoveWithUndo: () -> Unit = {
                    viewModel.removeDownload()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Download removed",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.startDownload()
                        }
                    }
                }
                val isExpanded =
                    windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
                if (isExpanded) {
                    LibraryItemDetailContentTablet(
                        item = state.item,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        audioDownloadState = audioDownloadState,
                        readaloudApplicable = state.isReadaloud,
                        isReadaloud = state.isReadaloud,
                        readaloudFooter = state.readaloudFooter,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onDownload = { viewModel.startDownload() },
                        onRemove = onRemoveWithUndo,
                        onDownloadReadaloudAudio = { viewModel.onDownloadReadaloudAudioClicked() },
                        onRemoveReadaloudAudio = { viewModel.onRemoveReadaloudAudioClicked() },
                        onUnlinkReadaloud = { viewModel.unlinkFromAbs() },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LibraryItemDetailContent(
                        item = state.item,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        audioDownloadState = audioDownloadState,
                        readaloudApplicable = state.isReadaloud,
                        isReadaloud = state.isReadaloud,
                        readaloudFooter = state.readaloudFooter,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onDownload = { viewModel.startDownload() },
                        onRemove = onRemoveWithUndo,
                        onDownloadReadaloudAudio = { viewModel.onDownloadReadaloudAudioClicked() },
                        onRemoveReadaloudAudio = { viewModel.onRemoveReadaloudAudioClicked() },
                        onUnlinkReadaloud = { viewModel.unlinkFromAbs() },
                        modifier = Modifier.padding(padding),
                    )
                }

                ReadaloudAudioDialogs(
                    state = audioDownloadState,
                    onConfirmDownload = { wifiOnly -> viewModel.confirmDownloadAudio(wifiOnly) },
                    onConfirmRemove = { viewModel.confirmRemoveAudio() },
                    onDismiss = { viewModel.dismissAudioDownloadDialog() },
                )
            }
        }
    }
}

@Composable
private fun CollapsibleDescription(description: String) {
    var expanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.animateContentSize()) {
        Text(text = "Summary", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) isOverflowing = result.hasVisualOverflow },
            modifier = Modifier.clickable(enabled = isOverflowing || expanded) { expanded = !expanded },
        )
        if (isOverflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show more")
            }
        }
    }
}

@Composable
private fun LibraryItemDetailContent(
    item: LibraryItem,
    isInToRead: Boolean,
    token: String,
    downloadState: DownloadState,
    audioDownloadState: AudioDownloadState,
    readaloudApplicable: Boolean,
    isReadaloud: Boolean,
    readaloudFooter: ReadaloudFooterState?,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloudAudio: () -> Unit,
    onRemoveReadaloudAudio: () -> Unit,
    onUnlinkReadaloud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item.coverUrl?.let { url ->
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .addHeader("Authorization", "Bearer $token")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .then(if (isLandscape) Modifier.fillMaxWidth(0.4f) else Modifier.fillMaxWidth())
                    .aspectRatio(2f / 3f)
                    .align(Alignment.CenterHorizontally),
            )
        }

        ActionRow(
            item = item,
            isInToRead = isInToRead,
            downloadState = downloadState,
            audioDownloadState = audioDownloadState,
            readaloudApplicable = readaloudApplicable,
            isReadaloud = isReadaloud,
            isCachedOrDownloaded = isCachedOrDownloaded,
            isOffline = isOffline,
            onReadItem = onReadItem,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
            onToggleToRead = onToggleToRead,
            onDownload = onDownload,
            onRemove = onRemove,
            onDownloadReadaloudAudio = onDownloadReadaloudAudio,
            onRemoveReadaloudAudio = onRemoveReadaloudAudio,
        )

        Text(text = item.title, style = MaterialTheme.typography.headlineMedium)
        Text(text = "By ${item.author}", style = MaterialTheme.typography.titleLarge)

        item.seriesName?.let { series ->
            Text(text = series, style = MaterialTheme.typography.bodyLarge)
        }

        if (item.readingProgress > 0f) {
            ReadingProgressIndicator(progress = item.readingProgress)
        }

        item.description?.takeIf { it.isNotBlank() }?.let { desc ->
            CollapsibleDescription(desc)
        }

        MetadataLines(item = item)

        readaloudFooter?.let { ReadaloudFooter(state = it, onUnlink = onUnlinkReadaloud) }
    }
}

@Composable
private fun ReadaloudFooter(state: ReadaloudFooterState, onUnlink: () -> Unit) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                when (state) {
                    is ReadaloudFooterState.AbsHasReadaloud -> {
                        Text(
                            text = "Readaloud available — open from ${state.readaloudLibraryName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ReadaloudFooterState.ReadaloudLinkedToAbs -> {
                        Text(text = "Linked to:", style = MaterialTheme.typography.bodyMedium)
                        state.targets.forEach { target ->
                            Text(
                                text = "• ${target.absTitle} · ${target.absLibraryName}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (state is ReadaloudFooterState.ReadaloudLinkedToAbs) {
                IconButton(onClick = onUnlink) {
                    Icon(
                        imageVector = Icons.Filled.LinkOff,
                        contentDescription = "Unlink",
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryItemDetailContentTablet(
    item: LibraryItem,
    isInToRead: Boolean,
    token: String,
    downloadState: DownloadState,
    isReadaloud: Boolean,
    readaloudFooter: ReadaloudFooterState?,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onUnlinkReadaloud: () -> Unit,
    modifier: Modifier = Modifier,
    audioDownloadState: AudioDownloadState = AudioDownloadState.NotDownloaded,
    readaloudApplicable: Boolean = false,
    onDownloadReadaloudAudio: () -> Unit = {},
    onRemoveReadaloudAudio: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .testTag(LIBRARY_ITEM_DETAIL_LEFT_PANE_TAG)
                .width(360.dp)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.coverUrl?.let { url ->
                // The left pane is non-scrolling (CONTEXT.md / ADR 0020), so the cover
                // must yield height to the action row. weight(fill = false) lets the
                // cover claim its aspect-ratio preferred size when there's room, but
                // shrink in landscape so the Read button stays visible.
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .addHeader("Authorization", "Bearer $token")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(2f / 3f)
                        .align(Alignment.CenterHorizontally),
                )
            }
            Text(text = item.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = "By ${item.author}", style = MaterialTheme.typography.titleLarge)
            if (item.readingProgress > 0f) {
                ReadingProgressIndicator(progress = item.readingProgress)
            }
            ActionRow(
                item = item,
                isInToRead = isInToRead,
                downloadState = downloadState,
                audioDownloadState = audioDownloadState,
                readaloudApplicable = readaloudApplicable,
                isReadaloud = isReadaloud,
                isCachedOrDownloaded = isCachedOrDownloaded,
                isOffline = isOffline,
                onReadItem = onReadItem,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                onToggleToRead = onToggleToRead,
                onDownload = onDownload,
                onRemove = onRemove,
                onDownloadReadaloudAudio = onDownloadReadaloudAudio,
                onRemoveReadaloudAudio = onRemoveReadaloudAudio,
            )
        }
        Column(
            modifier = Modifier
                .testTag(LIBRARY_ITEM_DETAIL_RIGHT_PANE_TAG)
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                CollapsibleDescription(desc)
            }
            item.seriesName?.let { series ->
                Text(text = series, style = MaterialTheme.typography.bodyLarge)
            }
            MetadataLines(item = item)
            readaloudFooter?.let { ReadaloudFooter(state = it, onUnlink = onUnlinkReadaloud) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(
    item: LibraryItem,
    isInToRead: Boolean,
    downloadState: DownloadState,
    audioDownloadState: AudioDownloadState,
    readaloudApplicable: Boolean,
    isReadaloud: Boolean,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloudAudio: () -> Unit,
    onRemoveReadaloudAudio: () -> Unit,
) {
    if (item.isSupported) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val readDisabledByOffline = isReadaloud && isOffline && !isCachedOrDownloaded
            if (readDisabledByOffline) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Connect to download book") } },
                    state = rememberTooltipState(),
                    modifier = Modifier.weight(1f),
                ) {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Read")
                    }
                }
            } else {
                Button(
                    onClick = { onReadItem(item) },
                    enabled = downloadState !is DownloadState.InProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Read")
                }
            }
            if (!isReadaloud) {
                ReadToggleButton(
                    isRead = item.readingProgress >= READ_PROGRESS_THRESHOLD,
                    onMarkAsRead = onMarkAsRead,
                    onMarkAsUnread = onMarkAsUnread,
                )
                ToReadToggleButton(
                    isInToRead = isInToRead,
                    onToggle = onToggleToRead,
                )
            }
            DownloadButton(
                state = downloadState,
                onDownload = onDownload,
                onRemove = onRemove,
            )
            if (readaloudApplicable) {
                ReadaloudAudioButton(
                    state = audioDownloadState,
                    onDownload = onDownloadReadaloudAudio,
                    onRemove = onRemoveReadaloudAudio,
                )
            }
        }
    } else {
        Text(
            text = "No ebook file is available for this item on the server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Second download action in the action row: downloads the Readaloud audio (the Storyteller synced
 * bundle, ADR 0023), distinct from the EPUB [DownloadButton]. The headphones icon signals "audio".
 */
@Composable
private fun ReadaloudAudioButton(
    state: AudioDownloadState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = 40.dp
    when (state) {
        is AudioDownloadState.Probing, is AudioDownloadState.InProgress -> {
            Box(
                modifier = modifier.size(size),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(size))
            }
        }
        is AudioDownloadState.Downloaded -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = "Remove readaloud audio",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        else -> {
            // NotDownloaded / Confirming / ConfirmingRemove / Error all present the affordance to
            // (re)start a download; dialogs handle the confirm/remove flows.
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = "Download readaloud audio",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Renders the confirm-download and confirm-remove dialogs for the Readaloud audio bundle. */
@Composable
private fun ReadaloudAudioDialogs(
    state: AudioDownloadState,
    onConfirmDownload: (wifiOnly: Boolean) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is AudioDownloadState.Confirming -> {
            var wifiOnly by remember { mutableStateOf(true) }
            val sizeLabel = state.sizeBytes?.let { formatBytes(it) } ?: "unknown size"
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Download readaloud audio ($sizeLabel)") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Download the readaloud audio for offline listening.")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Wi-Fi only")
                            Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirmDownload(wifiOnly) }) { Text("Download") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            )
        }
        is AudioDownloadState.ConfirmingRemove -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Remove readaloud audio") },
                text = { Text("This will free ${formatBytes(state.sizeBytes)}.") },
                confirmButton = {
                    TextButton(onClick = onConfirmRemove) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            )
        }
        else -> Unit
    }
}

/** Human-readable byte size, e.g. "1.2 GB" / "340 MB" / "512 KB". */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return if (mb < 10) String.format("%.1f MB", mb) else "${mb.toInt()} MB"
    val gb = mb / 1024.0
    return String.format("%.1f GB", gb)
}

@Composable
private fun ReadingProgressIndicator(progress: Float) {
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(progress * 100).toInt()}% read",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MetadataLines(item: LibraryItem) {
    val metadataItems = buildList {
        item.publishedYear?.let { add("Published: $it") }
        if (item.genres.isNotEmpty()) add("Genres: ${item.genres.joinToString(", ")}")
        item.publisher?.let { add("Publisher: $it") }
    }
    if (metadataItems.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            metadataItems.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
