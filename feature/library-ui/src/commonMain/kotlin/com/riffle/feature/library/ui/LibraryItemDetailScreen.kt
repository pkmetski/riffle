package com.riffle.feature.library.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import com.riffle.feature.library.FacetType
import com.riffle.feature.designsystem.CoverImage
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.BookDownloadOutcome
import com.riffle.feature.library.BookImportState
import com.riffle.feature.library.ChaptersState
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.TocState
import com.riffle.feature.library.UploadDestination
import com.riffle.feature.library.UploadPreflight
import com.riffle.feature.library.bookDownloadAffordances
import com.riffle.feature.library.bookDownloadOutcome
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_add_to_playlist
import com.riffle.feature.library.ui.generated.resources.ui_audiobook
import com.riffle.feature.library.ui.generated.resources.ui_audiobook_duration_line
import com.riffle.feature.library.ui.generated.resources.ui_back
import com.riffle.feature.library.ui.generated.resources.ui_by
import com.riffle.feature.library.ui.generated.resources.ui_cancel
import com.riffle.feature.library.ui.generated.resources.ui_chapters_count
import com.riffle.feature.library.ui.generated.resources.ui_connect_to_download_audiobook
import com.riffle.feature.library.ui.generated.resources.ui_connect_to_download_book
import com.riffle.feature.library.ui.generated.resources.ui_connect_to_download_readaloud_audio
import com.riffle.feature.library.ui.generated.resources.ui_connect_to_stream_audio
import com.riffle.feature.library.ui.generated.resources.ui_duration_total
import com.riffle.feature.library.ui.generated.resources.ui_duration_total_remaining
import com.riffle.feature.library.ui.generated.resources.ui_edit_metadata
import com.riffle.feature.library.ui.generated.resources.ui_genres
import com.riffle.feature.library.ui.generated.resources.ui_in_to_read
import com.riffle.feature.library.ui.generated.resources.ui_language
import com.riffle.feature.library.ui.generated.resources.ui_listen
import com.riffle.feature.library.ui.generated.resources.ui_mark_as_read
import com.riffle.feature.library.ui.generated.resources.ui_mark_as_unread
import com.riffle.feature.library.ui.generated.resources.ui_nothing_to_read_or_listen
import com.riffle.feature.library.ui.generated.resources.ui_pages
import com.riffle.feature.library.ui.generated.resources.ui_pages_read
import com.riffle.feature.library.ui.generated.resources.ui_progress_listened
import com.riffle.feature.library.ui.generated.resources.ui_progress_read
import com.riffle.feature.library.ui.generated.resources.ui_published
import com.riffle.feature.library.ui.generated.resources.ui_publisher
import com.riffle.feature.library.ui.generated.resources.ui_read
import com.riffle.feature.library.ui.generated.resources.ui_reading_time_estimated
import com.riffle.feature.library.ui.generated.resources.ui_reading_time_estimated_total
import com.riffle.feature.library.ui.generated.resources.ui_reading_time_estimated_total_remaining
import com.riffle.feature.library.ui.generated.resources.ui_readaloud
import com.riffle.feature.library.ui.generated.resources.ui_sections_count
import com.riffle.feature.library.ui.generated.resources.ui_show_all_readalouds
import com.riffle.feature.library.ui.generated.resources.ui_show_less
import com.riffle.feature.library.ui.generated.resources.ui_show_more
import com.riffle.feature.library.ui.generated.resources.ui_summary
import com.riffle.feature.library.ui.generated.resources.ui_to_read
import com.riffle.feature.library.ui.generated.resources.ui_upload_to
import com.riffle.feature.source.ui.RiffleMessageScaffold
import com.riffle.feature.source.ui.generated.resources.Res as SourceRes
import com.riffle.feature.source.ui.generated.resources.ui_back_with_arrow
import com.riffle.feature.source.ui.generated.resources.ui_download_complete
import com.riffle.feature.source.ui.generated.resources.ui_download_failed
import com.riffle.feature.source.ui.generated.resources.ui_item_not_found
import com.riffle.feature.source.ui.generated.resources.ui_loading
import com.riffle.feature.source.ui.generated.resources.ui_you_are_offline
import com.riffle.feature.source.ui.library.DownloadButton
import com.riffle.feature.source.ui.library.ReadaloudDownloadButton
import com.riffle.feature.source.ui.rememberTransientMessages
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

private const val READ_PROGRESS_THRESHOLD = 0.99f
private const val EXPANDED_WIDTH_BREAKPOINT_DP = 600

/**
 * Item detail screen — the full-parity shared implementation rendered by both Android and iOS.
 *
 * Implements all gaps documented in GitHub issue #1145 (feat(ios): item-detail parity).
 */
@Composable
fun LibraryItemDetailScreen(
    itemId: String,
    sourceId: String?,
    onBack: () -> Unit,
    onRead: (LibraryItem) -> Unit,
    onListen: (LibraryItem) -> Unit = {},
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    onFacetSelected: (libraryId: String, facet: FacetType, value: String) -> Unit = { _, _, _ -> },
    onNavigateToSeries: (libraryId: String, seriesId: String, seriesName: String) -> Unit = { _, _, _ -> },
) {
    val vm: LibraryItemDetailViewModel = koinInject(parameters = { parametersOf(itemId, sourceId) })

    val uiState by vm.uiState.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val audiobookDownloadState by vm.audiobookDownloadState.collectAsState()
    val readaloudDownloadState by vm.readaloudDownloadState.collectAsState()
    val tocState by vm.tocState.collectAsState()
    val chaptersState by vm.chaptersState.collectAsState()
    val currentPositionHref by vm.currentPositionHref.collectAsState()
    val estimatedTotalReadingTimeSec by vm.estimatedTotalReadingTimeSec.collectAsState()
    val pdfPageCount by vm.pdfPageCount.collectAsState()
    val epubVersion by vm.epubVersion.collectAsState()
    val uploadDestinations by vm.uploadDestinations.collectAsState()
    val uploadPreflight by vm.uploadPreflight.collectAsState()
    val bookImportState by vm.bookImportState.collectAsState()
    val showKoFiNudge by vm.showKoFiNudge.collectAsState()

    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showUploadDestinationDialog by remember { mutableStateOf(false) }

    // Refresh position, local availability, and server progress each time this effect fires.
    // Fires on first composition (itemId changes) — a full ON_RESUME hook requires
    // lifecycle-runtime-compose which is not yet a library-ui dependency.
    LaunchedEffect(vm) {
        vm.reloadCurrentPositionHref()
        vm.refreshLocalAvailability()
        vm.refreshItemProgress()
    }

    val messages = rememberTransientMessages()
    val completeMessage = stringResource(SourceRes.string.ui_download_complete)
    val failedMessage = stringResource(SourceRes.string.ui_download_failed)
    var previousDownloadState by remember { mutableStateOf<DownloadState?>(null) }
    LaunchedEffect(downloadState) {
        when (bookDownloadOutcome(previousDownloadState, downloadState)) {
            BookDownloadOutcome.Completed -> messages.show(completeMessage)
            BookDownloadOutcome.Failed -> messages.show(failedMessage)
            null -> Unit
        }
        previousDownloadState = downloadState
    }

    // Snackbar events from the VM (metadata save success, upload progress, etc.)
    LaunchedEffect(vm) {
        vm.snackbarEvents.collect { messages.show(it) }
    }

    if (showAddToPlaylistSheet) {
        val readyState = uiState as? LibraryItemDetailUiState.Ready
        if (readyState != null) {
            LaunchedEffect(showAddToPlaylistSheet) { vm.refreshPlaylists() }
            AddToPlaylistSheet(
                itemId = readyState.item.id,
                playlistsFlow = vm.playlistsForCurrentItem,
                labels = PlaylistLabels.English,
                onToggle = { playlist -> vm.toggleItemInPlaylist(playlist) },
                onCreate = { name -> vm.createPlaylistWithCurrentItem(name) },
                onDismiss = { showAddToPlaylistSheet = false },
            )
        }
    }

    // Upload-destination picker dialog
    if (showUploadDestinationDialog) {
        val readyState = uiState as? LibraryItemDetailUiState.Ready
        if (readyState != null) {
            LaunchedEffect(showUploadDestinationDialog) { vm.refreshUploadDestinations() }
            AlertDialog(
                onDismissRequest = { showUploadDestinationDialog = false },
                title = { Text(stringResource(Res.string.ui_upload_to)) },
                text = {
                    Column {
                        if (uploadDestinations.isEmpty()) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            uploadDestinations.forEach { destination ->
                                Text(destination.label, style = MaterialTheme.typography.titleSmall)
                                if (destination.username.isNotBlank()) {
                                    Text(destination.username, style = MaterialTheme.typography.bodySmall)
                                }
                                destination.libraries.forEach { library ->
                                    TextButton(onClick = {
                                        showUploadDestinationDialog = false
                                        vm.checkUploadDestination(destination, library)
                                    }) { Text(library.name) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showUploadDestinationDialog = false }) {
                        Text(stringResource(Res.string.ui_cancel))
                    }
                },
            )
        }
    }

    // Upload preflight dialogs (existing item / blocked)
    when (val pf = uploadPreflight) {
        is UploadPreflight.ExistingItem -> AlertDialog(
            onDismissRequest = { vm.dismissUploadPreflight() },
            title = { Text("Upload conflict") },
            text = { Text("An item with the same title already exists in ${pf.destination.label}. Overwrite it?") },
            confirmButton = {
                TextButton(onClick = { vm.importToDestination(pf.destination, pf.library) }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUploadPreflight() }) { Text(stringResource(Res.string.ui_cancel)) }
            },
        )
        is UploadPreflight.Blocked -> AlertDialog(
            onDismissRequest = { vm.dismissUploadPreflight() },
            title = { Text("Cannot upload") },
            text = { Text(pf.reason) },
            confirmButton = {
                TextButton(onClick = { vm.dismissUploadPreflight() }) { Text("OK") }
            },
        )
        else -> Unit
    }

    val isExpandedWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() >= EXPANDED_WIDTH_BREAKPOINT_DP.dp
    }

    when (val state = uiState) {
        LibraryItemDetailUiState.Loading -> DetailLoadingContent(onBack = onBack, bookImportState = bookImportState)
        LibraryItemDetailUiState.Error -> DetailErrorContent(onBack = onBack)
        is LibraryItemDetailUiState.Ready -> {
            DetailReadyScaffold(
                state = state,
                bookImportState = bookImportState,
                onBack = onBack,
                showOverflowMenu = showOverflowMenu,
                onToggleOverflowMenu = { showOverflowMenu = !showOverflowMenu },
                onDismissOverflowMenu = { showOverflowMenu = false },
                onUploadTo = { showOverflowMenu = false; showUploadDestinationDialog = true },
                messages = messages,
            ) { innerPadding ->
                if (isExpandedWidth) {
                    DetailContentTablet(
                        modifier = Modifier.padding(innerPadding),
                        state = state,
                        vm = vm,
                        token = vm.authToken,
                        downloadState = downloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        readaloudDownloadState = readaloudDownloadState,
                        tocState = tocState,
                        chaptersState = chaptersState,
                        currentPositionHref = currentPositionHref,
                        estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec,
                        pdfPageCount = pdfPageCount,
                        epubVersion = epubVersion,
                        showKoFiNudge = showKoFiNudge,
                        onRead = { onRead(state.item) },
                        onListen = { onListen(state.item) },
                        onReadAtHref = { href -> onReadItemAtHref(state.item, href) },
                        onListenAtSec = { sec -> onListenItemAtSec(state.item, sec) },
                        onMarkAsRead = { vm.markAsRead() },
                        onMarkAsUnread = { vm.markAsUnread() },
                        onToggleToRead = { vm.toggleToRead() },
                        onAddToPlaylist = { showAddToPlaylistSheet = true },
                        onFacet = { facet, value -> onFacetSelected(state.item.libraryId, facet, value) },
                        onSeries = { onNavigateToSeries(state.item.libraryId, it, state.item.seriesName?.substringBeforeLast(" #")?.trim() ?: "") },
                        onDismissKoFi = { vm.dismissKoFiNudge() },
                        onDownload = { vm.startDownload() },
                        onRemove = { vm.removeDownload() },
                        onDownloadAudiobook = { vm.onDownloadAudiobook() },
                        onRemoveAudiobook = { vm.onRemoveAudiobook() },
                        onDownloadReadaloud = { vm.onDownloadReadaloud() },
                        onRemoveReadaloud = { vm.onRemoveReadaloud() },
                    )
                } else {
                    DetailContentPhone(
                        modifier = Modifier.padding(innerPadding),
                        state = state,
                        vm = vm,
                        token = vm.authToken,
                        downloadState = downloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        readaloudDownloadState = readaloudDownloadState,
                        tocState = tocState,
                        chaptersState = chaptersState,
                        currentPositionHref = currentPositionHref,
                        estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec,
                        pdfPageCount = pdfPageCount,
                        epubVersion = epubVersion,
                        showKoFiNudge = showKoFiNudge,
                        onRead = { onRead(state.item) },
                        onListen = { onListen(state.item) },
                        onReadAtHref = { href -> onReadItemAtHref(state.item, href) },
                        onListenAtSec = { sec -> onListenItemAtSec(state.item, sec) },
                        onMarkAsRead = { vm.markAsRead() },
                        onMarkAsUnread = { vm.markAsUnread() },
                        onToggleToRead = { vm.toggleToRead() },
                        onAddToPlaylist = { showAddToPlaylistSheet = true },
                        onFacet = { facet, value -> onFacetSelected(state.item.libraryId, facet, value) },
                        onSeries = { onNavigateToSeries(state.item.libraryId, it, state.item.seriesName?.substringBeforeLast(" #")?.trim() ?: "") },
                        onDismissKoFi = { vm.dismissKoFiNudge() },
                        onDownload = { vm.startDownload() },
                        onRemove = { vm.removeDownload() },
                        onDownloadAudiobook = { vm.onDownloadAudiobook() },
                        onRemoveAudiobook = { vm.onRemoveAudiobook() },
                        onDownloadReadaloud = { vm.onDownloadReadaloud() },
                        onRemoveReadaloud = { vm.onRemoveReadaloud() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailReadyScaffold(
    state: LibraryItemDetailUiState.Ready,
    bookImportState: BookImportState,
    onBack: () -> Unit,
    showOverflowMenu: Boolean,
    onToggleOverflowMenu: () -> Unit,
    onDismissOverflowMenu: () -> Unit,
    onUploadTo: () -> Unit,
    messages: com.riffle.feature.source.ui.TransientMessages,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
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
                            Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                        }
                    },
                    actions = {
                        if (state.capabilities.canEditMetadata || state.capabilities.canUploadToConfiguredSource) {
                            Box {
                                IconButton(onClick = onToggleOverflowMenu) {
                                    Icon(LibraryUiGlyphs.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = onDismissOverflowMenu,
                                ) {
                                    if (state.capabilities.canUploadToConfiguredSource) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.ui_upload_to)) },
                                            onClick = onUploadTo,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                // Import progress bar below the TopAppBar when a book is being imported
                if (bookImportState is BookImportState.InProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { innerPadding ->
        RiffleMessageScaffold(messages) {
            content(innerPadding)
        }
    }
}

@Composable
private fun DetailLoadingContent(onBack: () -> Unit, bookImportState: BookImportState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(SourceRes.string.ui_loading), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (bookImportState is BookImportState.InProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator()
            }
        }
    }
}

@Composable
private fun DetailErrorContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(SourceRes.string.ui_item_not_found), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text(stringResource(SourceRes.string.ui_back_with_arrow)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Phone (portrait) layout — single scrolling column, action row first
// ---------------------------------------------------------------------------

@Composable
private fun DetailContentPhone(
    modifier: Modifier = Modifier,
    state: LibraryItemDetailUiState.Ready,
    vm: LibraryItemDetailViewModel,
    token: String,
    downloadState: DownloadState,
    audiobookDownloadState: DownloadState?,
    readaloudDownloadState: DownloadState?,
    tocState: TocState,
    chaptersState: ChaptersState,
    currentPositionHref: String?,
    estimatedTotalReadingTimeSec: Long?,
    pdfPageCount: Int?,
    epubVersion: String?,
    showKoFiNudge: Boolean,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onReadAtHref: (String) -> Unit,
    onListenAtSec: (Double) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFacet: (FacetType, String) -> Unit,
    onSeries: (String) -> Unit,
    onDismissKoFi: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadAudiobook: () -> Unit,
    onRemoveAudiobook: () -> Unit,
    onDownloadReadaloud: () -> Unit,
    onRemoveReadaloud: () -> Unit,
) {
    var showTocSheet by rememberSaveable { mutableStateOf(false) }
    var showChaptersSheet by rememberSaveable { mutableStateOf(false) }

    if (showTocSheet) {
        val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
        ItemTocSheet(entries = entries, activeHref = currentPositionHref, onEntryClick = { entry ->
            showTocSheet = false
            onReadAtHref(entry.href)
        }, onDismiss = { showTocSheet = false })
    }
    if (showChaptersSheet) {
        val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
        ItemChaptersBottomSheet(chapters = chapters, onChapterClick = { chapter ->
            showChaptersSheet = false
            onListenAtSec(chapter.startSec)
        }, onDismiss = { showChaptersSheet = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverBox(item = state.item, token = token)
        AudiobookDurationLine(item = state.item)
        PublicationFactsLine(item = state.item, estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec, pdfPageCount = pdfPageCount)
        ReadingProgressIndicator(item = state.item)
        ActionRow(
            item = state.item,
            isInToRead = state.isInToRead,
            capabilities = state.capabilities,
            downloadState = downloadState,
            isCachedOrDownloaded = state.isCachedOrDownloaded,
            isOffline = state.isOffline,
            audiobookDownloadState = audiobookDownloadState,
            readaloudDownloadState = readaloudDownloadState,
            onRead = onRead,
            onListen = onListen,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
            onToggleToRead = onToggleToRead,
            onAddToPlaylist = onAddToPlaylist,
            onDownload = onDownload,
            onRemove = onRemove,
            onDownloadAudiobook = onDownloadAudiobook,
            onRemoveAudiobook = onRemoveAudiobook,
            onDownloadReadaloud = onDownloadReadaloud,
            onRemoveReadaloud = onRemoveReadaloud,
        )
        TitleWithReadaloudBadge(
            title = state.item.title,
            hasReadaloud = state.capabilities.hasReadaloud,
            onReadaloudClick = { onFacet(FacetType.READALOUD, state.item.id) },
        )
        AuthorByline(author = state.item.author, onAuthorClick = { onFacet(FacetType.AUTHOR, it) })
        state.item.seriesName?.takeIf { it.isNotBlank() }?.let { seriesName ->
            SeriesLine(seriesName = seriesName, seriesId = state.seriesId, onSeriesClick = onSeries)
        }
        TocRow(tocState = tocState, onOpen = { showTocSheet = true })
        ChaptersRow(chaptersState = chaptersState, onOpen = { showChaptersSheet = true })
        state.item.description?.takeIf { it.isNotBlank() }?.let { desc ->
            CollapsibleDescription(html = desc)
        }
        MetadataLines(item = state.item, epubVersion = epubVersion, onFacet = onFacet)
        if (state.isOffline && !state.isCachedOrDownloaded) {
            Text(
                text = stringResource(SourceRes.string.ui_you_are_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (showKoFiNudge) {
            KoFiNudge(onDismiss = onDismissKoFi)
        }
    }
}

// ---------------------------------------------------------------------------
// Tablet (expanded-width) layout — two-pane
// ---------------------------------------------------------------------------

@Composable
private fun DetailContentTablet(
    modifier: Modifier = Modifier,
    state: LibraryItemDetailUiState.Ready,
    vm: LibraryItemDetailViewModel,
    token: String,
    downloadState: DownloadState,
    audiobookDownloadState: DownloadState?,
    readaloudDownloadState: DownloadState?,
    tocState: TocState,
    chaptersState: ChaptersState,
    currentPositionHref: String?,
    estimatedTotalReadingTimeSec: Long?,
    pdfPageCount: Int?,
    epubVersion: String?,
    showKoFiNudge: Boolean,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onReadAtHref: (String) -> Unit,
    onListenAtSec: (Double) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFacet: (FacetType, String) -> Unit,
    onSeries: (String) -> Unit,
    onDismissKoFi: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadAudiobook: () -> Unit,
    onRemoveAudiobook: () -> Unit,
    onDownloadReadaloud: () -> Unit,
    onRemoveReadaloud: () -> Unit,
) {
    var showTocSheet by rememberSaveable { mutableStateOf(false) }
    var showChaptersSheet by rememberSaveable { mutableStateOf(false) }

    if (showTocSheet) {
        val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
        ItemTocSheet(entries = entries, activeHref = currentPositionHref, onEntryClick = { entry ->
            showTocSheet = false
            onReadAtHref(entry.href)
        }, onDismiss = { showTocSheet = false })
    }
    if (showChaptersSheet) {
        val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
        ItemChaptersBottomSheet(chapters = chapters, onChapterClick = { chapter ->
            showChaptersSheet = false
            onListenAtSec(chapter.startSec)
        }, onDismiss = { showChaptersSheet = false })
    }

    Row(modifier = modifier.fillMaxSize()) {
        // Left pane — cover + primary CTAs (non-scrolling)
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxHeight()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverBox(item = state.item, token = token)
            TitleWithReadaloudBadge(
                title = state.item.title,
                hasReadaloud = state.capabilities.hasReadaloud,
                onReadaloudClick = { onFacet(FacetType.READALOUD, state.item.id) },
            )
            AuthorByline(author = state.item.author, onAuthorClick = { onFacet(FacetType.AUTHOR, it) })
            AudiobookDurationLine(item = state.item)
            ReadingProgressIndicator(item = state.item)
            ActionRow(
                item = state.item,
                isInToRead = state.isInToRead,
                capabilities = state.capabilities,
                downloadState = downloadState,
                isCachedOrDownloaded = state.isCachedOrDownloaded,
                isOffline = state.isOffline,
                audiobookDownloadState = audiobookDownloadState,
                readaloudDownloadState = readaloudDownloadState,
                onRead = onRead,
                onListen = onListen,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                onToggleToRead = onToggleToRead,
                onAddToPlaylist = onAddToPlaylist,
                onDownload = onDownload,
                onRemove = onRemove,
                onDownloadAudiobook = onDownloadAudiobook,
                onRemoveAudiobook = onRemoveAudiobook,
                onDownloadReadaloud = onDownloadReadaloud,
                onRemoveReadaloud = onRemoveReadaloud,
            )
        }
        // Right pane — metadata (scrolling)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PublicationFactsLine(item = state.item, estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec, pdfPageCount = pdfPageCount)
            state.item.seriesName?.takeIf { it.isNotBlank() }?.let { seriesName ->
                SeriesLine(seriesName = seriesName, seriesId = state.seriesId, onSeriesClick = onSeries)
            }
            TocRow(tocState = tocState, onOpen = { showTocSheet = true })
            ChaptersRow(chaptersState = chaptersState, onOpen = { showChaptersSheet = true })
            state.item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                CollapsibleDescription(html = desc)
            }
            MetadataLines(item = state.item, epubVersion = epubVersion, onFacet = onFacet)
            if (state.isOffline && !state.isCachedOrDownloaded) {
                Text(
                    text = stringResource(SourceRes.string.ui_you_are_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showKoFiNudge) {
                KoFiNudge(onDismiss = onDismissKoFi)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun CoverBox(item: LibraryItem, token: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .aspectRatio(if (item.isAudiobookOnly) 1f else 2f / 3f)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        CoverImage(
            url = item.coverUrl,
            token = token,
            contentDescription = null,
            isAudiobook = item.isAudiobookOnly,
            instrumentationKind = "detail",
            instrumentationKey = item.id,
        )
    }
}

@Composable
private fun AudiobookDurationLine(item: LibraryItem) {
    if (!item.isListenable || item.audioDurationSec <= 0) return
    val durationStr = formatCompactDuration(item.audioDurationSec)
    val readingProgress = item.readingProgress
    val text = when {
        readingProgress >= READ_PROGRESS_THRESHOLD ->
            stringResource(Res.string.ui_duration_total, durationStr)
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * item.audioDurationSec).coerceAtLeast(0.0)
            stringResource(Res.string.ui_duration_total_remaining, durationStr, formatCompactDuration(remainingSec))
        }
        else -> stringResource(Res.string.ui_audiobook_duration_line, durationStr)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PublicationFactsLine(item: LibraryItem, estimatedTotalReadingTimeSec: Long?, pdfPageCount: Int?) {
    val text: String? = when (item.ebookFormat) {
        EbookFormat.Epub -> estimatedTotalReadingTimeSec?.let { totalSec ->
            val total = formatCompactDuration(totalSec.toDouble())
            when {
                item.readingProgress >= READ_PROGRESS_THRESHOLD ->
                    stringResource(Res.string.ui_reading_time_estimated_total, total)
                item.readingProgress > 0f -> {
                    val rem = formatCompactDuration(((1f - item.readingProgress) * totalSec).coerceAtLeast(0f).toDouble())
                    stringResource(Res.string.ui_reading_time_estimated_total_remaining, total, rem)
                }
                else -> stringResource(Res.string.ui_reading_time_estimated, total)
            }
        }
        EbookFormat.Pdf -> {
            val count = pdfPageCount ?: item.pageCount
            count?.takeIf { it > 0 }?.let { pages ->
                if (!item.readingProgress.isFinite() || item.readingProgress <= 0f) {
                    stringResource(Res.string.ui_pages, pages)
                } else {
                    val read = (pages * item.readingProgress).roundToInt().coerceIn(1, pages)
                    stringResource(Res.string.ui_pages_read, read, pages)
                }
            }
        }
        EbookFormat.Cbz -> item.pageCount?.takeIf { it > 0 }?.let { pages ->
            stringResource(Res.string.ui_pages, pages)
        }
        EbookFormat.Unsupported -> null
    }
    if (text.isNullOrBlank()) return
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReadingProgressIndicator(item: LibraryItem) {
    val progress = item.readingProgress
    if (progress <= 0f) return
    val listened = item.isAudiobookOnly
    Column {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BOOK_DETAIL_PROGRESS),
        )
        Spacer(Modifier.height(4.dp))
        val pct = (progress * 100).roundToInt().coerceIn(0, 100)
        Text(
            text = if (listened) stringResource(Res.string.ui_progress_listened, pct) else stringResource(Res.string.ui_progress_read, pct),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ActionRow(
    item: LibraryItem,
    isInToRead: Boolean,
    capabilities: DetailCapabilities,
    downloadState: DownloadState,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    audiobookDownloadState: DownloadState?,
    readaloudDownloadState: DownloadState?,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadAudiobook: () -> Unit,
    onRemoveAudiobook: () -> Unit,
    onDownloadReadaloud: () -> Unit,
    onRemoveReadaloud: () -> Unit,
) {
    val effectivelyListenable = item.isListenable && capabilities.hasAudiobookMedia
    if (!item.isReadable && !effectivelyListenable) {
        Text(
            text = stringResource(Res.string.ui_nothing_to_read_or_listen),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isReadable) {
            val readDisabledByOffline = isOffline && !isCachedOrDownloaded
            Button(
                onClick = if (readDisabledByOffline) ({}) else onRead,
                enabled = !readDisabledByOffline && downloadState !is DownloadState.InProgress,
                modifier = Modifier.weight(1f).testTag(TestTags.BOOK_DETAIL_OPEN),
            ) { Text(stringResource(Res.string.ui_read)) }
        }
        if (effectivelyListenable) {
            val audiobookAvailableOffline = audiobookDownloadState == DownloadState.Downloaded || audiobookDownloadState == DownloadState.Cached
            val listenBlockedOffline = isOffline && !audiobookAvailableOffline && readaloudDownloadState != DownloadState.Downloaded
            Button(
                onClick = if (listenBlockedOffline) ({}) else onListen,
                enabled = !listenBlockedOffline,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.ui_listen)) }
        }
        if (capabilities.hasMarkRead) {
            ReadToggleButton(
                isRead = item.readingProgress >= READ_PROGRESS_THRESHOLD,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                modifier = Modifier.testTag(TestTags.BOOK_DETAIL_MARK_READ),
            )
        }
        if (capabilities.hasPlaylists) {
            ToReadToggleButton(isInToRead = isInToRead, onToggle = onToggleToRead)
        }
        if (capabilities.hasAddToPlaylist) {
            AddToPlaylistButton(onClick = onAddToPlaylist)
        }
        val affordances = bookDownloadAffordances(
            item = item,
            capabilities = capabilities,
            isOffline = isOffline,
            audiobookDownloadState = audiobookDownloadState,
            readaloudDownloadState = readaloudDownloadState,
        )
        if (affordances.showEbook) {
            DownloadButton(state = downloadState, onDownload = onDownload, onRemove = onRemove)
        }
        if (affordances.showAudiobook && audiobookDownloadState != null) {
            DownloadButton(state = audiobookDownloadState, onDownload = onDownloadAudiobook, onRemove = onRemoveAudiobook, enabled = affordances.audiobookEnabled)
        }
        if (affordances.showReadaloud && readaloudDownloadState != null) {
            ReadaloudDownloadButton(state = readaloudDownloadState, onDownload = onDownloadReadaloud, onRemove = onRemoveReadaloud, enabled = affordances.readaloudEnabled)
        }
    }
}

@Composable
private fun ReadToggleButton(isRead: Boolean, onMarkAsRead: () -> Unit, onMarkAsUnread: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (isRead) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            .clickable(onClick = if (isRead) onMarkAsUnread else onMarkAsRead),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LibraryUiGlyphs.Check,
            contentDescription = if (isRead) stringResource(Res.string.ui_mark_as_unread) else stringResource(Res.string.ui_mark_as_read),
            tint = if (isRead) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ToReadToggleButton(isInToRead: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (isInToRead) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isInToRead) RiffleIcons.ToReadFilled else RiffleIcons.ToRead,
            contentDescription = if (isInToRead) stringResource(Res.string.ui_in_to_read) else stringResource(Res.string.ui_to_read),
            tint = if (isInToRead) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AddToPlaylistButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LibraryUiGlyphs.QueueMusic,
            contentDescription = stringResource(Res.string.ui_add_to_playlist),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TitleWithReadaloudBadge(title: String, hasReadaloud: Boolean, onReadaloudClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(TestTags.BOOK_DETAIL_TITLE),
        )
        if (hasReadaloud) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = RiffleIcons.ReadaloudBadge,
                contentDescription = stringResource(Res.string.ui_show_all_readalouds),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp).clickable(onClick = onReadaloudClick),
            )
        }
    }
}

@Composable
private fun AuthorByline(author: String, onAuthorClick: (String) -> Unit) {
    if (author.isBlank()) return
    val authors = author.split(", ").filter { it.isNotBlank() }
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = androidx.compose.ui.graphics.Color.Unspecified
    val annotated = buildAnnotatedString {
        append(stringResource(Res.string.ui_by) + " ")
        authors.forEachIndexed { index, token ->
            withLink(
                LinkAnnotation.Clickable(
                    tag = "author",
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                    linkInteractionListener = { onAuthorClick(token) },
                ),
            ) { append(token) }
            if (index < authors.lastIndex) append(", ")
        }
    }
    Text(text = annotated, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun SeriesLine(seriesName: String, seriesId: String?, onSeriesClick: (String) -> Unit) {
    val bareName = seriesName.substringBeforeLast(" #").trim()
    if (seriesId != null) {
        Text(
            text = seriesName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onSeriesClick(seriesId) },
        )
    } else {
        Text(text = seriesName, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TocRow(tocState: TocState, onOpen: () -> Unit) {
    val ready = tocState as? TocState.Ready ?: return
    if (ready.entries.isEmpty()) return
    val sectionCount = countFlatEntries(ready.entries)
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_sections_count, sectionCount)) },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun ChaptersRow(chaptersState: ChaptersState, onOpen: () -> Unit) {
    val ready = chaptersState as? ChaptersState.Ready ?: return
    if (ready.chapters.isEmpty()) return
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_chapters_count, ready.chapters.size)) },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun CollapsibleDescription(html: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(Res.string.ui_summary), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        val text = remember(html) { htmlBlurb(html) }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        )
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.Start)) {
            Text(if (expanded) stringResource(Res.string.ui_show_less) else stringResource(Res.string.ui_show_more))
        }
    }
}

@Composable
private fun MetadataLines(item: LibraryItem, epubVersion: String?, onFacet: (FacetType, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.publishedYear?.takeIf { it.isNotBlank() }?.let { year ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.ui_published), style = MaterialTheme.typography.bodyMedium)
                Text(year, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onFacet(FacetType.YEAR, year) })
            }
        }
        if (item.genres.isNotEmpty()) {
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = buildAnnotatedString {
                append(stringResource(Res.string.ui_genres))
                item.genres.forEachIndexed { index, genre ->
                    withLink(LinkAnnotation.Clickable("genre", TextLinkStyles(SpanStyle(color = linkColor)), linkInteractionListener = { onFacet(FacetType.GENRE, genre) })) { append(genre) }
                    if (index < item.genres.lastIndex) append(", ")
                }
            }
            Text(text = annotated, style = MaterialTheme.typography.bodyMedium)
        }
        item.language?.takeIf { it.isNotBlank() }?.let { language ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.ui_language), style = MaterialTheme.typography.bodyMedium)
                Text(language, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onFacet(FacetType.LANGUAGE, language) })
            }
        }
        item.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
            Text(stringResource(Res.string.ui_publisher, publisher), style = MaterialTheme.typography.bodyMedium)
        }
        val formatLabel = when (item.ebookFormat) {
            EbookFormat.Epub -> if (!epubVersion.isNullOrBlank()) "EPUB ${epubVersion}" else "EPUB"
            EbookFormat.Pdf -> "PDF"
            EbookFormat.Cbz -> "CBZ"
            EbookFormat.Unsupported -> null
        }
        formatLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun KoFiNudge(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Enjoying Riffle? Consider supporting development. ☕", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ---------------------------------------------------------------------------
// TOC / chapters bottom sheets (inline — no dependency on feature:settings-ui)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemTocSheet(
    entries: List<TocEntry>,
    activeHref: String?,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(flattenTocEntries(entries), key = { "${it.depth}/${it.entry.href}" }) { row ->
                val isActive = row.entry.href == activeHref
                ListItem(
                    headlineContent = {
                        Text(
                            text = row.entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    },
                    modifier = Modifier
                        .padding(start = (row.depth * 16).dp)
                        .clickable { onEntryClick(row.entry) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemChaptersBottomSheet(
    chapters: List<AudiobookChapter>,
    onChapterClick: (AudiobookChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(chapters, key = { it.index }) { chapter ->
                ListItem(
                    headlineContent = { Text(chapter.title, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text(formatCompactDuration(chapter.startSec), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.clickable { onChapterClick(chapter) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pure helper functions — shared derivation, pinnable by tests
// ---------------------------------------------------------------------------

private data class FlatTocRow(val entry: TocEntry, val depth: Int)

private fun flattenTocEntries(entries: List<TocEntry>, depth: Int = 0): List<FlatTocRow> =
    entries.flatMap { entry -> listOf(FlatTocRow(entry, depth)) + flattenTocEntries(entry.children, depth + 1) }

private fun countFlatEntries(entries: List<TocEntry>): Int =
    entries.sumOf { 1 + countFlatEntries(it.children) }

/** Compact h/m duration string — local implementation since feature:player isn't on the classpath. */
internal fun formatCompactDuration(durationSec: Double): String {
    val total = durationSec.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * Reading time text for EPUB items. Produces "X estimated", "X estimated total", or
 * "X estimated total · Y remaining" depending on progress.
 */
internal fun ebookReadingTimeText(
    totalSec: Long,
    readingProgress: Float,
    estimated: (String) -> String = { "$it estimated" },
    estimatedTotal: (String) -> String = { "$it estimated total" },
    estimatedTotalRemaining: (String, String) -> String = { total, rem -> "$total estimated total · $rem remaining" },
): String {
    val total = formatCompactDuration(totalSec.toDouble())
    return when {
        readingProgress >= READ_PROGRESS_THRESHOLD -> estimatedTotal(total)
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * totalSec).toLong().coerceAtLeast(0L)
            estimatedTotalRemaining(total, formatCompactDuration(remainingSec.toDouble()))
        }
        else -> estimated(total)
    }
}

/** "N pages" or "M of N pages read" depending on progress. */
internal fun pageCountText(
    pages: Int,
    readingProgress: Float,
    formatPages: (Int) -> String = { "$it pages" },
    formatPagesRead: (Int, Int) -> String = { read, total -> "$read of $total pages read" },
): String {
    if (pages <= 0) return ""
    if (!readingProgress.isFinite() || readingProgress <= 0f) return formatPages(pages)
    val pagesRead = (pages * readingProgress).roundToInt().coerceIn(1, pages)
    return formatPagesRead(pagesRead, pages)
}
