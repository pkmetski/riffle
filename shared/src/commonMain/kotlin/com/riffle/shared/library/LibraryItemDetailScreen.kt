package com.riffle.shared.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.CoverImage
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.BookDownloadOutcome
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.bookDownloadOutcome
import com.riffle.feature.library.ui.AddToPlaylistSheet
import com.riffle.feature.library.ui.PlaylistLabels
import com.riffle.feature.source.ui.RiffleMessageScaffold
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_download_complete
import com.riffle.feature.source.ui.generated.resources.ui_download_failed
import com.riffle.feature.source.ui.library.BookDownloadControls
import com.riffle.feature.source.ui.rememberTransientMessages
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val READ_PROGRESS_THRESHOLD = 0.99f

private val AuthorStyle = TextStyle(fontSize = 16.sp)
private val MetadataStyle = TextStyle(fontSize = 13.sp)

/**
 * The item detail sheet.
 *
 * [onRead] receives the loaded [LibraryItem] so the host can route it to a reader — the sheet only
 * knows `(itemId, sourceId)` on the way in, and surfaces that open it from an annotated book never
 * hold a [LibraryItem] at all. Hosts pass `readerNavForItem`; a host that dismisses here instead
 * makes the Read button a no-op.
 */
@Composable
fun LibraryItemDetailScreen(
    itemId: String,
    sourceId: String?,
    onBack: () -> Unit,
    onRead: (LibraryItem) -> Unit,
    onFacetSelected: (libraryId: String, facet: FacetType, value: String) -> Unit,
) {
    val vm: LibraryItemDetailViewModel = koinInject(parameters = { parametersOf(itemId, sourceId) })
    val uiState by vm.uiState.collectAsState()
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    // The three download states the shared BookDownloadControls needs. The ViewModel has always
    // exposed them and always had working iOS repositories behind them; nothing on this platform
    // collected them, so a file only ever reached the (evictable) cache as a side effect of
    // opening the book.
    val downloadState by vm.downloadState.collectAsState()
    val audiobookDownloadState by vm.audiobookDownloadState.collectAsState()
    val readaloudDownloadState by vm.readaloudDownloadState.collectAsState()

    when (val state = uiState) {
        LibraryItemDetailUiState.Loading -> LoadingContent()
        LibraryItemDetailUiState.Error -> ErrorContent(onBack = onBack)
        is LibraryItemDetailUiState.Ready -> {
            if (showAddToPlaylistSheet) {
                LaunchedEffect(showAddToPlaylistSheet) { vm.refreshPlaylists() }
                AddToPlaylistSheet(
                    itemId = state.item.id,
                    playlistsFlow = vm.playlistsForCurrentItem,
                    labels = PlaylistLabels.English,
                    onToggle = { playlist -> vm.toggleItemInPlaylist(playlist) },
                    onCreate = { name -> vm.createPlaylistWithCurrentItem(name) },
                    onDismiss = { showAddToPlaylistSheet = false },
                )
            }
            ReadyContent(
                state = state,
                token = vm.authToken,
                onBack = onBack,
                onRead = { onRead(state.item) },
                onToggleToRead = { vm.toggleToRead() },
                onMarkAsRead = { vm.markAsRead() },
                onMarkAsUnread = { vm.markAsUnread() },
                downloadControls = {
                    // One call into the shared control rather than a second iOS-only copy of the
                    // three buttons. A host that restructures this screen keeps calling this.
                    BookDownloadControls(
                        item = state.item,
                        capabilities = state.capabilities,
                        isOffline = state.isOffline,
                        downloadState = downloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        readaloudDownloadState = readaloudDownloadState,
                        onDownloadEbook = { vm.startDownload() },
                        onRemoveEbook = { vm.removeDownload() },
                        onDownloadAudiobook = { vm.onDownloadAudiobook() },
                        onRemoveAudiobook = { vm.onRemoveAudiobook() },
                        onDownloadReadaloud = { vm.onDownloadReadaloud() },
                        onRemoveReadaloud = { vm.onRemoveReadaloud() },
                    )
                },
                downloadState = downloadState,
                // `null` hides the button: DetailCapabilities gating, honoured rather than
                // rendering a control the Source cannot back.
                onAddToPlaylist = if (state.capabilities.hasAddToPlaylist) {
                    { showAddToPlaylistSheet = true }
                } else {
                    null
                },
                onFacet = { facet, value -> onFacetSelected(state.item.libraryId, facet, value) },
            )
        }
    }
}

/**
 * A row of tappable facet values. Renders nothing when [values] is empty, so an item with no
 * genres (or no year, or no language) shows no stray blank line.
 */
@Composable
private fun FacetRow(
    values: List<String>,
    style: TextStyle,
    onClick: (String) -> Unit,
) {
    if (values.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            Text(
                text = value,
                style = style,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .testTag(TestTags.facet(value))
                    .clickable { onClick(value) },
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Item not found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/**
 * The loaded sheet.
 *
 * `internal` rather than private so `ItemDetailDownloadMountTest` can drive it: whether this
 * screen mounts a download control at all — and whether a finished download says anything — is
 * not derivable from any pure function, and it is exactly the wiring that was missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadyContent(
    state: LibraryItemDetailUiState.Ready,
    token: String,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleToRead: () -> Unit,
    onMarkAsRead: () -> Unit = {},
    onMarkAsUnread: () -> Unit = {},
    downloadControls: @Composable () -> Unit,
    downloadState: DownloadState,
    onAddToPlaylist: (() -> Unit)?,
    onFacet: (FacetType, String) -> Unit,
) {
    val messages = rememberTransientMessages()
    // A download that fails is otherwise indistinguishable from one that was never started: the
    // ring just turns back into an outlined circle. bookDownloadOutcome reads the transition and
    // the snackbar says which one happened.
    var previousDownloadState by remember { mutableStateOf<DownloadState?>(null) }
    val completeMessage = stringResource(Res.string.ui_download_complete)
    val failedMessage = stringResource(Res.string.ui_download_failed)
    LaunchedEffect(downloadState) {
        when (bookDownloadOutcome(previousDownloadState, downloadState)) {
            BookDownloadOutcome.Completed -> messages.show(completeMessage)
            BookDownloadOutcome.Failed -> messages.show(failedMessage)
            null -> Unit
        }
        previousDownloadState = downloadState
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(TestTags.BOOK_DETAIL_BACK),
                    ) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        RiffleMessageScaffold(messages) {
            ReadyBody(
                modifier = Modifier.padding(innerPadding),
                state = state,
                token = token,
                onRead = onRead,
                onToggleToRead = onToggleToRead,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                downloadControls = downloadControls,
                onAddToPlaylist = onAddToPlaylist,
                onFacet = onFacet,
            )
        }
    }
}

@Composable
private fun ReadyBody(
    modifier: Modifier = Modifier,
    state: LibraryItemDetailUiState.Ready,
    token: String,
    onRead: () -> Unit,
    onToggleToRead: () -> Unit,
    onMarkAsRead: () -> Unit = {},
    onMarkAsUnread: () -> Unit = {},
    downloadControls: @Composable () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onFacet: (FacetType, String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cover — full-width, matching Android's phone-portrait "full-bleed" rule.
        // Tablet portrait will need a widthIn(max=280dp) cap when iOS tablet layout is supported.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (state.item.isAudiobookOnly) 1f else 2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            CoverImage(
                url = state.item.coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = state.item.isAudiobookOnly,
                instrumentationKind = "detail",
                instrumentationKey = state.item.id,
            )
        }

        // Audiobook duration / page count — same line Android's PublicationFactsLine renders.
        PublicationFactsLine(state.item)

        if (state.item.readingProgress > 0f) {
            LinearProgressIndicator(
                progress = { state.item.readingProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.BOOK_DETAIL_PROGRESS),
            )
        }

        // Action buttons — matches Android's order: CTA row first, then title/metadata below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRead,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.BOOK_DETAIL_OPEN),
            ) {
                Text("Read")
            }
            OutlinedButton(
                onClick = onToggleToRead,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isInToRead) "In To-Read" else "To Read")
            }
        }

        if (onAddToPlaylist != null) {
            OutlinedButton(
                onClick = onAddToPlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.DETAIL_ADD_TO_PLAYLIST),
            ) {
                Text(PlaylistLabels.English.addToPlaylist)
            }
        }

        if (state.capabilities.hasMarkRead) {
            val isRead = state.item.readingProgress >= READ_PROGRESS_THRESHOLD
            if (isRead) {
                OutlinedButton(
                    onClick = onMarkAsUnread,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.BOOK_DETAIL_MARK_READ),
                ) {
                    Text("Mark as unread")
                }
            } else {
                OutlinedButton(
                    onClick = onMarkAsRead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.BOOK_DETAIL_MARK_READ),
                ) {
                    Text("Mark as read")
                }
            }
        }

        // Title and byline come AFTER the action row — matching Android's phone-portrait layout
        // where the CTA is the primary visual, not the title.
        Text(
            text = state.item.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag(TestTags.BOOK_DETAIL_TITLE),
        )

        FacetRow(
            values = state.item.author.split(", ").filter { it.isNotBlank() },
            style = AuthorStyle,
            onClick = { onFacet(FacetType.AUTHOR, it) },
        )

        state.item.seriesName?.takeIf { it.isNotBlank() }?.let { seriesName ->
            Text(
                text = seriesName,
                style = MetadataStyle.copy(color = MaterialTheme.colorScheme.primary),
            )
        }

        downloadControls()

        state.item.description?.takeIf { it.isNotBlank() }?.let { description ->
            CollapsibleDescription(description)
        }

        // Genre / year / language chips — same facets Android's MetadataLines offers.
        FacetRow(
            values = state.item.genres,
            style = MetadataStyle,
            onClick = { onFacet(FacetType.GENRE, it) },
        )
        FacetRow(
            values = listOfNotNull(state.item.publishedYear?.takeIf { it.isNotBlank() }),
            style = MetadataStyle,
            onClick = { onFacet(FacetType.YEAR, it) },
        )
        FacetRow(
            values = listOfNotNull(state.item.language?.takeIf { it.isNotBlank() }),
            style = MetadataStyle,
            onClick = { onFacet(FacetType.LANGUAGE, it) },
        )

        FormatLine(state.item.ebookFormat)

        if (state.isOffline) {
            Text(
                text = "You are offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** "EPUB", "PDF", or "CBZ" label — mirrors Android's `FormatLine`. */
@Composable
private fun FormatLine(format: EbookFormat) {
    val label = when (format) {
        EbookFormat.Epub -> "EPUB"
        EbookFormat.Pdf -> "PDF"
        EbookFormat.Cbz -> "CBZ"
        EbookFormat.Unsupported -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Page count or audiobook duration — mirrors Android's `PublicationFactsLine` /
 * `AudiobookDurationLine`. Skipped when no discrete count exists (EPUB reflowable text,
 * audiobook-only without a duration).
 */
@Composable
private fun PublicationFactsLine(item: LibraryItem) {
    val text = when {
        item.isAudiobookOnly && item.audioDurationSec > 0 -> formatAudioDuration(item.audioDurationSec)
        item.ebookFormat == EbookFormat.Cbz || item.ebookFormat == EbookFormat.Pdf ->
            item.pageCount?.takeIf { it > 0 }?.let { "$it pages" }
        else -> null
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Compact h/m format matching the pattern Android's `formatCompactDuration` produces. */
private fun formatAudioDuration(durationSec: Double): String {
    val total = durationSec.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun CollapsibleDescription(description: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Summary",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(if (expanded) "Show less" else "Show more")
        }
    }
}
