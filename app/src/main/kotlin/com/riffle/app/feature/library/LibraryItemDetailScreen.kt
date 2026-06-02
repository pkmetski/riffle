package com.riffle.app.feature.library

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.material3.Surface
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
    onReviewReadaloud: (String) -> Unit = {},
    onPairReadaloud: (String, String) -> Unit = { _, _ -> },
    viewModel: LibraryItemDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
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
                // Removal is immediate with no Undo: for a Storyteller bundle, re-downloading on
                // Undo silently re-pulls hundreds of MB — the exact data burn the explicit-download
                // model exists to prevent. Re-downloading is a deliberate Download tap (ADR 0024).
                val onRemove: () -> Unit = {
                    viewModel.removeDownload()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Download removed",
                            duration = SnackbarDuration.Short,
                        )
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
                        isReadaloud = state.isReadaloud,
                        readaloudFooter = state.readaloudFooter,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onDownload = { viewModel.startDownload() },
                        onRemove = onRemove,
                        onUnlinkReadaloud = { viewModel.unlinkFromAbs() },
                        onReviewReadaloud = onReviewReadaloud,
                        onPairReadaloud = onPairReadaloud,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LibraryItemDetailContent(
                        item = state.item,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        isReadaloud = state.isReadaloud,
                        readaloudFooter = state.readaloudFooter,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onDownload = { viewModel.startDownload() },
                        onRemove = onRemove,
                        onUnlinkReadaloud = { viewModel.unlinkFromAbs() },
                        onReviewReadaloud = onReviewReadaloud,
                        onPairReadaloud = onPairReadaloud,
                        modifier = Modifier.padding(padding),
                    )
                }
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
    onReviewReadaloud: (String) -> Unit = {},
    onPairReadaloud: (String, String) -> Unit = { _, _ -> },
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
            isReadaloud = isReadaloud,
            isCachedOrDownloaded = isCachedOrDownloaded,
            isOffline = isOffline,
            onReadItem = onReadItem,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
            onToggleToRead = onToggleToRead,
            onDownload = onDownload,
            onRemove = onRemove,
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

        readaloudFooter?.let {
            ReadaloudFooter(
                state = it,
                onUnlink = onUnlinkReadaloud,
                onReviewReadaloud = onReviewReadaloud,
                onPairReadaloud = onPairReadaloud,
            )
        }
    }
}

@Composable
private fun ReadaloudFooter(
    state: ReadaloudFooterState,
    onUnlink: () -> Unit,
    onReviewReadaloud: (String) -> Unit = {},
    onPairReadaloud: (String, String) -> Unit = { _, _ -> },
) {
    // Tap-navigation targets per ADR 0021: Pending Review opens the review queue; Unmatched opens
    // the manual-pair picker for this book.
    val rowClick: (() -> Unit)? = when (state) {
        is ReadaloudFooterState.ReadaloudPendingReview -> {
            { onReviewReadaloud(state.storytellerServerId) }
        }
        is ReadaloudFooterState.ReadaloudUnmatched -> {
            { onPairReadaloud(state.storytellerServerId, state.storytellerBookId) }
        }
        else -> null
    }
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier),
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
                    is ReadaloudFooterState.ReadaloudPendingReview -> {
                        Text(
                            text = "Possible matches — Review",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ReadaloudFooterState.ReadaloudUnmatched -> {
                        Text(
                            text = "Not linked to an ABS book — Pair manually",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
    onReviewReadaloud: (String) -> Unit = {},
    onPairReadaloud: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
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
                isReadaloud = isReadaloud,
                isCachedOrDownloaded = isCachedOrDownloaded,
                isOffline = isOffline,
                onReadItem = onReadItem,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                onToggleToRead = onToggleToRead,
                onDownload = onDownload,
                onRemove = onRemove,
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
            readaloudFooter?.let {
                ReadaloudFooter(
                    state = it,
                    onUnlink = onUnlinkReadaloud,
                    onReviewReadaloud = onReviewReadaloud,
                    onPairReadaloud = onPairReadaloud,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(
    item: LibraryItem,
    isInToRead: Boolean,
    downloadState: DownloadState,
    isReadaloud: Boolean,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
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
            // For a Storyteller (Readaloud) book the synced bundle is BOTH the EPUB and the audio
            // source (ADR 0023), so the single DownloadButton already downloads/removes the audio.
            // A separate readaloud download/remove control here would be redundant and, worse, would
            // delete the very bundle the reader is using — so it is intentionally absent. Listening
            // happens in the reader (its headphones action); proactively fetching the audio for a
            // matched ABS book is the next slice's concern.
            DownloadButton(
                state = downloadState,
                onDownload = onDownload,
                onRemove = onRemove,
            )
        }
    } else {
        Text(
            text = "No ebook file is available for this item on the server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
