package com.riffle.app.feature.library

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.app.feature.reader.TocPanel
import com.riffle.app.ui.DefaultCoverPlaceholder
import com.riffle.app.ui.isPhoneLandscape
import com.riffle.app.ui.isTabletLayout
import com.riffle.app.ui.source.asAuthHeader
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val LIBRARY_ITEM_DETAIL_LEFT_PANE_TAG = "library_item_detail_left_pane"
const val LIBRARY_ITEM_DETAIL_RIGHT_PANE_TAG = "library_item_detail_right_pane"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemDetailScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onReadItem: (LibraryItem) -> Unit,
    onListenItem: (LibraryItem) -> Unit = {},
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    onNavigateToFacet: (libraryId: String, facet: FacetType, value: String) -> Unit = { _, _, _ -> },
    onNavigateToSeries: (libraryId: String, seriesId: String, seriesName: String) -> Unit = { _, _, _ -> },
    viewModel: LibraryItemDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val readaloudDownloadState by viewModel.readaloudDownloadState.collectAsState()
    val audiobookDownloadState by viewModel.audiobookDownloadState.collectAsState()
    val tocState by viewModel.tocState.collectAsState()
    val chaptersState by viewModel.chaptersState.collectAsState()
    val currentPositionHref by viewModel.currentPositionHref.collectAsState()
    val estimatedTotalReadingTimeSec by viewModel.estimatedTotalReadingTimeSec.collectAsState()
    val pdfPageCount by viewModel.pdfPageCount.collectAsState()
    val epubVersion by viewModel.epubVersion.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showMetadataOverflowMenu by remember { mutableStateOf(false) }
    var showUploadDestinationDialog by remember { mutableStateOf(false) }
    var showEditMetadataDialog by remember { mutableStateOf(false) }
    val uploadDestinations by viewModel.uploadDestinations.collectAsState()
    val uploadPreflight by viewModel.uploadPreflight.collectAsState()
    val bookImportState by viewModel.bookImportState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(bookImportState) {
        bookImportSnackbarMessage(bookImportState)?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.reloadCurrentPositionHref()
            viewModel.refreshLocalAvailability()
            // Pull server progress for THIS item so the blue bar / % refresh on every visit,
            // even when the user reaches the details page without going through the library grid
            // (deep link, back from reader). The library-grid ON_RESUME is a separate trigger and
            // does not fire when only the details page is on screen.
            viewModel.refreshItemProgress()
        }
    }

    val readyState = uiState as? LibraryItemDetailUiState.Ready

    if (showEditMetadataDialog && readyState != null) {
        EditLocalFileMetadataDialog(
            item = readyState.item,
            originalItem = readyState.originalItem,
            onSave = { title, author, seriesName, seriesIndex, coverContentUri, clearCoverOverride ->
                viewModel.saveMetadataOverride(title, author, seriesName, seriesIndex, coverContentUri, clearCoverOverride)
                showEditMetadataDialog = false
            },
            onDismiss = { showEditMetadataDialog = false },
        )
    }

    if (showUploadDestinationDialog && readyState != null) {
        AlertDialog(
            onDismissRequest = { showUploadDestinationDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_upload_to)) },
            text = {
                Column {
                    if (uploadDestinations.isEmpty()) {
                        CircularProgressIndicator()
                    } else {
                        uploadDestinations.forEach { destination ->
                            Text(destination.label, style = MaterialTheme.typography.titleSmall)
                            if (destination.username.isNotBlank()) {
                                Text(
                                    destination.username,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (destination.libraries.isEmpty()) {
                                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_libraries_configured))
                            } else {
                                destination.libraries.forEach { library ->
                                    TextButton(
                                        onClick = {
                                            showUploadDestinationDialog = false
                                            viewModel.checkUploadDestination(destination, library)
                                        },
                                    ) {
                                        Text(library.name)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadDestinationDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel))
                }
            },
        )
    }

    when (val preflight = uploadPreflight) {
        is UploadPreflight.ExistingItem -> AlertDialog(
            onDismissRequest = viewModel::dismissUploadPreflight,
            title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_item_already_exists)) },
            text = {
                Text(
                    if (preflight.canOverwrite) {
                        "This audiobook is already in the destination library. Overwrite it?"
                    } else {
                        "Overwrite is blocked until the destination can safely replace files without invalidating annotations."
                    },
                )
            },
            confirmButton = {
                if (preflight.canOverwrite) {
                    TextButton(
                        onClick = {
                            viewModel.importToDestination(preflight.destination, preflight.library)
                        },
                    ) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_overwrite)) }
                } else {
                    TextButton(onClick = viewModel::dismissUploadPreflight) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_ok)) }
                }
            },
        )
        is UploadPreflight.Blocked -> AlertDialog(
            onDismissRequest = viewModel::dismissUploadPreflight,
            title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_upload_unavailable)) },
            text = { Text(preflight.reason) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissUploadPreflight) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_ok)) }
            },
        )
        UploadPreflight.Idle, UploadPreflight.Checking -> Unit
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (readyState != null) {
                            Column {
                                Text(text = readyState.item.title, maxLines = 1)
                                when (val importState = bookImportState) {
                                    is BookImportState.InProgress -> Text(
                                        text = when (importState.phase) {
                                            com.riffle.core.catalog.CatalogImportPhase.Preparing -> androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_preparing_files)
                                            com.riffle.core.catalog.CatalogImportPhase.Uploading -> androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_uploading)
                                            com.riffle.core.catalog.CatalogImportPhase.Reconciling -> androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_uploaded_waiting_for_abs)
                                            com.riffle.core.catalog.CatalogImportPhase.Finalizing -> androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_uploaded_applying_metadata)
                                            com.riffle.core.catalog.CatalogImportPhase.Uploaded -> androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_uploaded_finishing)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    is BookImportState.Failed -> Text(
                                        text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_upload_failed),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    BookImportState.Idle, BookImportState.Completed -> Unit
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
                        }
                    },
                    actions = {
                    if (readyState?.capabilities?.canEditMetadata == true ||
                        readyState?.capabilities?.canUploadToConfiguredSource == true
                    ) {
                        Box {
                            IconButton(onClick = { showMetadataOverflowMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_more_options))
                            }
                            DropdownMenu(
                                expanded = showMetadataOverflowMenu,
                                onDismissRequest = { showMetadataOverflowMenu = false },
                            ) {
                                if (readyState.capabilities.canEditMetadata) {
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_edit_metadata)) },
                                        onClick = {
                                            showMetadataOverflowMenu = false
                                            showEditMetadataDialog = true
                                        },
                                    )
                                }
                                if (readyState.capabilities.canUploadToConfiguredSource) {
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_upload_to)) },
                                        onClick = {
                                            showMetadataOverflowMenu = false
                                            showUploadDestinationDialog = true
                                            viewModel.refreshUploadDestinations()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    },
                )
                when (val state = bookImportState) {
                    is BookImportState.InProgress -> {
                        val progress = state.completedFiles.takeIf { state.totalFiles > 0 }
                            ?.toFloat()?.div(state.totalFiles)
                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    BookImportState.Idle, BookImportState.Completed, is BookImportState.Failed -> Unit
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Add-to-playlist sheet — driven by [showAddToPlaylistSheet], populated from the
        // ViewModel's PlaylistsRepository-backed flow. The "To Read" playlist is filtered out at
        // the repository layer so it never appears in this picker.
        if (showAddToPlaylistSheet && uiState is LibraryItemDetailUiState.Ready) {
            val readyState = uiState as LibraryItemDetailUiState.Ready
            LaunchedEffect(showAddToPlaylistSheet) { viewModel.refreshPlaylists() }
            com.riffle.app.feature.library.playlists.AddToPlaylistSheet(
                itemId = readyState.item.id,
                playlistsFlow = viewModel.playlistsForCurrentItem,
                onToggle = { pl -> viewModel.toggleItemInPlaylist(pl) },
                onCreate = { name -> viewModel.createPlaylistWithCurrentItem(name) },
                onDismiss = { showAddToPlaylistSheet = false },
            )
        }
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
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_could_not_load_book_details))
                }
            }

            is LibraryItemDetailUiState.Ready -> {
                val isTablet = windowSizeClass.isTabletLayout()
                // A phone in landscape is wide-but-short: it keeps the phone chrome (modal drawer) but
                // is still wide enough for a two-column cover-beside-text detail layout, which scrolls
                // far less than the single tall column the phone-portrait layout would give here.
                val isPhoneLandscape = windowSizeClass.isPhoneLandscape()
                val onFacet: (FacetType, String) -> Unit = { facet, value ->
                    onNavigateToFacet(state.item.libraryId, facet, value)
                }
                val onSeriesClick: (String, String) -> Unit = { seriesId, seriesName ->
                    onNavigateToSeries(state.item.libraryId, seriesId, seriesName)
                }
                if (isPhoneLandscape) {
                    LibraryItemDetailContentPhoneLandscape(
                        item = state.item,
                        seriesId = state.seriesId,
                        capabilities = state.capabilities,
                        onFacet = onFacet,
                        onSeriesClick = onSeriesClick,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        readaloudDownloadState = readaloudDownloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        tocState = tocState,
                        chaptersState = chaptersState,
                        currentPositionHref = currentPositionHref,
                        estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec,
                        pdfPageCount = pdfPageCount,
                        epubVersion = epubVersion,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onListenItem = { item -> viewModel.markOpened(); onListenItem(item) },
                        onReadItemAtHref = { item, href -> viewModel.markOpened(); onReadItemAtHref(item, href) },
                        onListenItemAtSec = { item, sec -> viewModel.markOpened(); onListenItemAtSec(item, sec) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onAddToPlaylist = { showAddToPlaylistSheet = true },
                        onDownload = { viewModel.startDownload() },
                        onRemove = viewModel::removeDownload,
                        onDownloadReadaloud = viewModel::onDownloadReadaloud,
                        onRemoveReadaloud = viewModel::onRemoveReadaloud,
                        onDownloadAudiobook = viewModel::onDownloadAudiobook,
                        onRemoveAudiobook = viewModel::onRemoveAudiobook,
                        modifier = Modifier.padding(padding),
                    )
                } else if (isTablet) {
                    LibraryItemDetailContentTablet(
                        item = state.item,
                        seriesId = state.seriesId,
                        capabilities = state.capabilities,
                        onFacet = onFacet,
                        onSeriesClick = onSeriesClick,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        readaloudDownloadState = readaloudDownloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        tocState = tocState,
                        chaptersState = chaptersState,
                        currentPositionHref = currentPositionHref,
                        estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec,
                        pdfPageCount = pdfPageCount,
                        epubVersion = epubVersion,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onListenItem = { item -> viewModel.markOpened(); onListenItem(item) },
                        onReadItemAtHref = { item, href -> viewModel.markOpened(); onReadItemAtHref(item, href) },
                        onListenItemAtSec = { item, sec -> viewModel.markOpened(); onListenItemAtSec(item, sec) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onAddToPlaylist = { showAddToPlaylistSheet = true },
                        onDownload = { viewModel.startDownload() },
                        onRemove = viewModel::removeDownload,
                        onDownloadReadaloud = viewModel::onDownloadReadaloud,
                        onRemoveReadaloud = viewModel::onRemoveReadaloud,
                        onDownloadAudiobook = viewModel::onDownloadAudiobook,
                        onRemoveAudiobook = viewModel::onRemoveAudiobook,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LibraryItemDetailContent(
                        item = state.item,
                        seriesId = state.seriesId,
                        capabilities = state.capabilities,
                        onFacet = onFacet,
                        onSeriesClick = onSeriesClick,
                        isInToRead = state.isInToRead,
                        token = viewModel.authToken,
                        downloadState = downloadState,
                        isCachedOrDownloaded = state.isCachedOrDownloaded,
                        isOffline = state.isOffline,
                        readaloudDownloadState = readaloudDownloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        tocState = tocState,
                        chaptersState = chaptersState,
                        currentPositionHref = currentPositionHref,
                        estimatedTotalReadingTimeSec = estimatedTotalReadingTimeSec,
                        pdfPageCount = pdfPageCount,
                        epubVersion = epubVersion,
                        onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
                        onListenItem = { item -> viewModel.markOpened(); onListenItem(item) },
                        onReadItemAtHref = { item, href -> viewModel.markOpened(); onReadItemAtHref(item, href) },
                        onListenItemAtSec = { item, sec -> viewModel.markOpened(); onListenItemAtSec(item, sec) },
                        onMarkAsRead = { viewModel.markAsRead() },
                        onMarkAsUnread = { viewModel.markAsUnread() },
                        onToggleToRead = { viewModel.toggleToRead() },
                        onAddToPlaylist = { showAddToPlaylistSheet = true },
                        onDownload = { viewModel.startDownload() },
                        onRemove = viewModel::removeDownload,
                        onDownloadReadaloud = viewModel::onDownloadReadaloud,
                        onRemoveReadaloud = viewModel::onRemoveReadaloud,
                        onDownloadAudiobook = viewModel::onDownloadAudiobook,
                        onRemoveAudiobook = viewModel::onRemoveAudiobook,
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
    val formatted = remember(description) { AnnotatedString.fromHtml(description) }

    Column(modifier = Modifier.animateContentSize()) {
        Text(text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_summary), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatted,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) isOverflowing = result.hasVisualOverflow },
            modifier = Modifier.clickable(enabled = isOverflowing || expanded) { expanded = !expanded },
        )
        if (isOverflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_show_less)
                    } else {
                        androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_show_more)
                    },
                )
            }
        }
    }
}

@Composable
internal fun LibraryItemDetailContent(
    item: LibraryItem,
    seriesId: String?,
    capabilities: DetailCapabilities = DetailCapabilities.All,
    onFacet: (FacetType, String) -> Unit,
    onSeriesClick: (String, String) -> Unit,
    isInToRead: Boolean,
    token: String,
    downloadState: DownloadState,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    readaloudDownloadState: DownloadState?,
    audiobookDownloadState: DownloadState? = null,
    tocState: TocState = TocState.Loading,
    chaptersState: ChaptersState = ChaptersState.Loading,
    currentPositionHref: String? = null,
    estimatedTotalReadingTimeSec: Long? = null,
    pdfPageCount: Int? = null,
    epubVersion: String? = null,
    onReadItem: (LibraryItem) -> Unit,
    onListenItem: (LibraryItem) -> Unit = {},
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloud: () -> Unit = {},
    onRemoveReadaloud: () -> Unit = {},
    onDownloadAudiobook: () -> Unit = {},
    onRemoveAudiobook: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showTocSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // A tablet in portrait is below the 840dp Expanded breakpoint (ADR 0019), so it
        // lands on this single-column phone layout. fillMaxWidth() makes the cover span
        // the whole tablet width — ginormous. Cap it on tablet-wide screens; real phones
        // (< 600dp) keep the full-bleed cover.
        val isWideScreen = configuration.screenWidthDp >= 600
        val coverWidth = when {
            isLandscape -> Modifier.fillMaxWidth(0.4f)
            isWideScreen -> Modifier.widthIn(max = 280.dp)
            else -> Modifier.fillMaxWidth()
        }
        val isAudiobook = item.isListenable && !item.isReadable
        Box(
            modifier = Modifier
                .then(coverWidth)
                .aspectRatio(if (isAudiobook) 1f else 2f / 3f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            DefaultCoverPlaceholder(isAudiobook = isAudiobook, modifier = Modifier.fillMaxSize())
            if (item.coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.coverUrl)
                        .addHeader("Authorization", token.asAuthHeader())
                        .instrumentCover("detail", item.id, item.coverUrl)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (item.isListenable && item.audioDurationSec > 0) {
            AudiobookDurationLine(item.audioDurationSec, item.readingProgress)
        }

        PublicationFactsLine(item, estimatedTotalReadingTimeSec, pdfPageCount)

        if (item.readingProgress > 0f) {
            ReadingProgressIndicator(progress = item.readingProgress, listened = item.isListenable && !item.isReadable)
        }

        ActionRow(
            item = item,
            isInToRead = isInToRead,
            capabilities = capabilities,
            downloadState = downloadState,
            isCachedOrDownloaded = isCachedOrDownloaded,
            isOffline = isOffline,
            readaloudDownloadState = readaloudDownloadState,
            audiobookDownloadState = audiobookDownloadState,
            onReadItem = onReadItem,
            onListenItem = onListenItem,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
            onToggleToRead = onToggleToRead,
            onAddToPlaylist = onAddToPlaylist,
            onDownload = onDownload,
            onRemove = onRemove,
            onDownloadReadaloud = onDownloadReadaloud,
            onRemoveReadaloud = onRemoveReadaloud,
            onDownloadAudiobook = onDownloadAudiobook,
            onRemoveAudiobook = onRemoveAudiobook,
        )

        TitleWithReadaloudIndicator(
            title = item.title,
            hasReadaloud = readaloudDownloadState != null,
            onReadaloudClick = { onFacet(FacetType.READALOUD, "all") },
        )
        AuthorByline(author = item.author, onAuthorClick = { onFacet(FacetType.AUTHOR, it) })

        if (capabilities.hasSeries) {
            item.seriesName?.let { series ->
                SeriesLine(seriesName = series, seriesId = seriesId, onSeriesClick = onSeriesClick)
            }
        }

        // TOC row — EPUB items only; hidden if TOC loaded as empty
        val tocReady = tocState as? TocState.Ready
        if (item.ebookFormat == EbookFormat.Epub && (tocState is TocState.Loading || tocReady?.entries?.isNotEmpty() == true)) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_table_of_contents)) },
                supportingContent = {
                    when (val s = tocState) {
                        is TocState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                        is TocState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_sections_count, s.entries.size))
                    }
                },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(
                    enabled = tocReady != null && tocReady.entries.isNotEmpty(),
                    onClick = { showTocSheet = true },
                ),
            )
        }

        // Chapters row — audiobook items; hidden if chapters loaded as empty
        val chaptersReady = chaptersState as? ChaptersState.Ready
        if (item.isListenable && (chaptersState is ChaptersState.Loading || chaptersReady?.chapters?.isNotEmpty() == true)) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters)) },
                supportingContent = {
                    when (val s = chaptersState) {
                        is ChaptersState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                        is ChaptersState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters_count, s.chapters.size))
                    }
                },
                leadingContent = {
                    Icon(Icons.Outlined.Headphones, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(
                    enabled = chaptersReady != null && chaptersReady.chapters.isNotEmpty(),
                    onClick = { showChaptersSheet = true },
                ),
            )
        }

        item.description?.takeIf { it.isNotBlank() }?.let { desc ->
            CollapsibleDescription(desc)
        }

        MetadataLines(item = item, onFacet = onFacet)
        FormatLine(item.ebookFormat, epubVersion)
    }

    if (showTocSheet) {
        val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
        TocPanel(
            entries = entries,
            activeHref = currentPositionHref,
            onEntryClick = { entry ->
                onReadItemAtHref(item, entry.href)
                showTocSheet = false
            },
            onDismiss = { showTocSheet = false },
        )
    }
    if (showChaptersSheet) {
        val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
        ItemChaptersSheet(
            chapters = chapters,
            onChapterClick = { chapter -> onListenItemAtSec(item, chapter.startSec) },
            onDismiss = { showChaptersSheet = false },
        )
    }
}

@Composable
private fun FormatLine(format: EbookFormat, epubVersion: String?) {
    val label = when (format) {
        EbookFormat.Epub -> if (epubVersion != null) "EPUB $epubVersion" else "EPUB"
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

@Composable
private fun PublicationFactsLine(
    item: LibraryItem,
    estimatedTotalReadingTimeSec: Long?,
    extractedPdfPageCount: Int?,
) {
    val fmtEstimated = stringResource(R.string.ui_reading_time_estimated)
    val fmtEstimatedTotal = stringResource(R.string.ui_reading_time_estimated_total)
    val fmtEstimatedTotalRemaining = stringResource(R.string.ui_reading_time_estimated_total_remaining)
    val fmtPages = stringResource(R.string.ui_pages)
    val fmtPagesRead = stringResource(R.string.ui_pages_of_read)
    val text = when (item.ebookFormat) {
        EbookFormat.Epub -> estimatedTotalReadingTimeSec?.let {
            ebookReadingTimeText(
                it, item.readingProgress,
                estimated = { d -> fmtEstimated.format(d) },
                estimatedTotal = { d -> fmtEstimatedTotal.format(d) },
                estimatedTotalRemaining = { d, r -> fmtEstimatedTotalRemaining.format(d, r) },
            )
        }
        EbookFormat.Pdf -> (extractedPdfPageCount ?: item.pageCount)
            ?.takeIf { it > 0 }
            ?.let { publicationPageCountText(it, item.readingProgress, { n -> fmtPages.format(n) }, { read, total -> fmtPagesRead.format(read, total) }) }
        EbookFormat.Cbz -> item.pageCount
            ?.takeIf { it > 0 }
            ?.let { publicationPageCountText(it, item.readingProgress, { n -> fmtPages.format(n) }, { read, total -> fmtPagesRead.format(read, total) }) }
        EbookFormat.Unsupported -> null
    }
    // EPUB reading time and the extracted PDF page count arrive asynchronously, seconds after
    // first render. Reserve the line for those formats even while the value is pending so the
    // Read button below never shifts under an in-flight tap once the value lands.
    if (text == null && !publicationFactsLineReservesSpace(item.ebookFormat)) return

    Text(
        text = text.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whether [PublicationFactsLine] must occupy its line even before its value resolves. EPUB
 * estimates and extracted PDF page counts are computed asynchronously after the detail screen
 * renders; appearing late must not reflow the action row (tap-target stability).
 */
internal fun publicationFactsLineReservesSpace(format: EbookFormat): Boolean =
    format == EbookFormat.Epub || format == EbookFormat.Pdf

internal fun ebookReadingTimeText(
    totalSec: Long,
    readingProgress: Float,
    estimated: (String) -> String = { "$it estimated" },
    estimatedTotal: (String) -> String = { "$it estimated total" },
    estimatedTotalRemaining: (String, String) -> String = { total, rem -> "$total estimated total · $rem remaining" },
): String {
    val total = formatDuration(totalSec)
    return when {
        readingProgress >= READ_PROGRESS_THRESHOLD -> estimatedTotal(total)
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * totalSec).toLong().coerceAtLeast(0L)
            estimatedTotalRemaining(total, formatDuration(remainingSec))
        }
        else -> estimated(total)
    }
}

internal fun publicationPageCountText(
    pageCount: Int,
    readingProgress: Float,
    formatPages: (Int) -> String = { "$it pages" },
    formatPagesRead: (Int, Int) -> String = { read, total -> "$read of $total pages read" },
): String {
    if (pageCount <= 0) return ""
    if (!readingProgress.isFinite() || readingProgress <= 0f) return formatPages(pageCount)

    val pagesRead = (pageCount * readingProgress)
        .roundToInt()
        .coerceIn(1, pageCount)
    return formatPagesRead(pagesRead, pageCount)
}

/** Total audiobook length on the detail screen, with remaining time when in progress (ADR 0035). */
@Composable
private fun AudiobookDurationLine(durationSec: Double, readingProgress: Float = 0f) {
    val text = when {
        readingProgress >= READ_PROGRESS_THRESHOLD -> "${formatAudiobookDuration(durationSec)} total"
        readingProgress > 0f -> {
            val remainingSec = ((1f - readingProgress) * durationSec).coerceAtLeast(0.0)
            "${formatAudiobookDuration(durationSec)} total · ${formatAudiobookDuration(remainingSec)} remaining"
        }
        else -> "Audiobook · ${formatAudiobookDuration(durationSec)}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatAudiobookDuration(durationSec: Double): String {
    return formatDuration(durationSec.toLong())
}

private fun formatDuration(durationSec: Long): String {
    val total = durationSec.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
internal fun LibraryItemDetailContentTablet(
    item: LibraryItem,
    seriesId: String? = null,
    capabilities: DetailCapabilities = DetailCapabilities.All,
    onFacet: (FacetType, String) -> Unit = { _, _ -> },
    onSeriesClick: (String, String) -> Unit = { _, _ -> },
    isInToRead: Boolean,
    token: String,
    downloadState: DownloadState,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    readaloudDownloadState: DownloadState?,
    audiobookDownloadState: DownloadState? = null,
    tocState: TocState = TocState.Loading,
    chaptersState: ChaptersState = ChaptersState.Loading,
    currentPositionHref: String? = null,
    estimatedTotalReadingTimeSec: Long? = null,
    pdfPageCount: Int? = null,
    epubVersion: String? = null,
    onReadItem: (LibraryItem) -> Unit,
    onListenItem: (LibraryItem) -> Unit = {},
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloud: () -> Unit = {},
    onRemoveReadaloud: () -> Unit = {},
    onDownloadAudiobook: () -> Unit = {},
    onRemoveAudiobook: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showTocSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }

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
            // The left pane is non-scrolling (CONTEXT.md / ADR 0020), so the cover
            // must yield height to the action row. weight(fill = false) lets the
            // cover shrink if it can't fit, keeping the Read button visible.
            //
            // The cap is orientation-aware: in portrait the pane is tall, so an
            // uncapped cover dominates — cap it small. In landscape the pane is
            // short and wide, so a small cap leaves the cover looking lost — allow
            // it larger (weight still shrinks it if the action row needs the room).
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isAudiobook = item.isListenable && !item.isReadable
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = if (isLandscape) 230.dp else 100.dp)
                    .aspectRatio(if (isAudiobook) 1f else 2f / 3f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                DefaultCoverPlaceholder(isAudiobook = isAudiobook, modifier = Modifier.fillMaxSize())
                if (item.coverUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.coverUrl)
                            .addHeader("Authorization", token.asAuthHeader())
                            .instrumentCover("detail", item.id, item.coverUrl)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            TitleWithReadaloudIndicator(
                title = item.title,
                hasReadaloud = readaloudDownloadState != null,
                onReadaloudClick = { onFacet(FacetType.READALOUD, "all") },
            )
            AuthorByline(author = item.author, onAuthorClick = { onFacet(FacetType.AUTHOR, it) })
            if (item.isListenable && item.audioDurationSec > 0) {
                AudiobookDurationLine(item.audioDurationSec, item.readingProgress)
            }
            PublicationFactsLine(item, estimatedTotalReadingTimeSec, pdfPageCount)
            if (item.readingProgress > 0f) {
                ReadingProgressIndicator(progress = item.readingProgress, listened = item.isListenable && !item.isReadable)
            }
            ActionRow(
                item = item,
                isInToRead = isInToRead,
                capabilities = capabilities,
                downloadState = downloadState,
                isCachedOrDownloaded = isCachedOrDownloaded,
                isOffline = isOffline,
                readaloudDownloadState = readaloudDownloadState,
                audiobookDownloadState = audiobookDownloadState,
                onReadItem = onReadItem,
                onListenItem = onListenItem,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                onToggleToRead = onToggleToRead,
            onAddToPlaylist = onAddToPlaylist,
                onDownload = onDownload,
                onRemove = onRemove,
                onDownloadReadaloud = onDownloadReadaloud,
                onRemoveReadaloud = onRemoveReadaloud,
                onDownloadAudiobook = onDownloadAudiobook,
                onRemoveAudiobook = onRemoveAudiobook,
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
            // TOC row — EPUB items only; hidden if TOC loaded as empty
            val tocReady = tocState as? TocState.Ready
            if (item.ebookFormat == EbookFormat.Epub && (tocState is TocState.Loading || tocReady?.entries?.isNotEmpty() == true)) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_table_of_contents)) },
                    supportingContent = {
                        when (val s = tocState) {
                            is TocState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                            is TocState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_sections_count, s.entries.size))
                        }
                    },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(
                        enabled = tocReady != null && tocReady.entries.isNotEmpty(),
                        onClick = { showTocSheet = true },
                    ),
                )
            }

            // Chapters row — audiobook items; hidden if chapters loaded as empty
            val chaptersReady = chaptersState as? ChaptersState.Ready
            if (item.isListenable && (chaptersState is ChaptersState.Loading || chaptersReady?.chapters?.isNotEmpty() == true)) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters)) },
                    supportingContent = {
                        when (val s = chaptersState) {
                            is ChaptersState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                            is ChaptersState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters_count, s.chapters.size))
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Headphones, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(
                        enabled = chaptersReady != null && chaptersReady.chapters.isNotEmpty(),
                        onClick = { showChaptersSheet = true },
                    ),
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                CollapsibleDescription(desc)
            }
            if (capabilities.hasSeries) {
                item.seriesName?.let { series ->
                    SeriesLine(seriesName = series, seriesId = seriesId, onSeriesClick = onSeriesClick)
                }
            }
            MetadataLines(item = item, onFacet = onFacet)
            FormatLine(item.ebookFormat, epubVersion)
        }
    }

    if (showTocSheet) {
        val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
        TocPanel(
            entries = entries,
            activeHref = currentPositionHref,
            onEntryClick = { entry ->
                onReadItemAtHref(item, entry.href)
                showTocSheet = false
            },
            onDismiss = { showTocSheet = false },
        )
    }
    if (showChaptersSheet) {
        val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
        ItemChaptersSheet(
            chapters = chapters,
            onChapterClick = { chapter -> onListenItemAtSec(item, chapter.startSec) },
            onDismiss = { showChaptersSheet = false },
        )
    }
}

/**
 * Detail layout for a phone in landscape (wide-but-short). The cover sits alone in the left column so
 * it gets the full screen height — a big cover — while everything else (title, author, actions,
 * Summary, metadata) scrolls in the wide right column. Keeping the cover out of the scrolling column
 * means far less scrolling than the single-column phone layout, and the wide right column leaves the
 * action row room to lay out horizontally (no squished Read/Listen button). Ebook + audiobook alike.
 */
@Composable
internal fun LibraryItemDetailContentPhoneLandscape(
    item: LibraryItem,
    seriesId: String? = null,
    capabilities: DetailCapabilities = DetailCapabilities.All,
    onFacet: (FacetType, String) -> Unit = { _, _ -> },
    onSeriesClick: (String, String) -> Unit = { _, _ -> },
    isInToRead: Boolean,
    token: String,
    downloadState: DownloadState,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    readaloudDownloadState: DownloadState?,
    audiobookDownloadState: DownloadState? = null,
    tocState: TocState = TocState.Loading,
    chaptersState: ChaptersState = ChaptersState.Loading,
    currentPositionHref: String? = null,
    estimatedTotalReadingTimeSec: Long? = null,
    pdfPageCount: Int? = null,
    epubVersion: String? = null,
    onReadItem: (LibraryItem) -> Unit,
    onListenItem: (LibraryItem) -> Unit = {},
    onReadItemAtHref: (LibraryItem, String) -> Unit = { _, _ -> },
    onListenItemAtSec: (LibraryItem, Double) -> Unit = { _, _ -> },
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloud: () -> Unit = {},
    onRemoveReadaloud: () -> Unit = {},
    onDownloadAudiobook: () -> Unit = {},
    onRemoveAudiobook: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showTocSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .testTag(LIBRARY_ITEM_DETAIL_LEFT_PANE_TAG)
                .fillMaxHeight()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isAudiobook = item.isListenable && !item.isReadable
            Box(
                modifier = Modifier
                    // The cover owns the column's full height; aspectRatio derives its width from that.
                    .fillMaxHeight()
                    .aspectRatio(if (isAudiobook) 1f else 2f / 3f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                DefaultCoverPlaceholder(isAudiobook = isAudiobook, modifier = Modifier.fillMaxSize())
                if (item.coverUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.coverUrl)
                            .addHeader("Authorization", token.asAuthHeader())
                            .instrumentCover("detail", item.id, item.coverUrl)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
            TitleWithReadaloudIndicator(
                title = item.title,
                hasReadaloud = readaloudDownloadState != null,
                onReadaloudClick = { onFacet(FacetType.READALOUD, "all") },
            )
            AuthorByline(author = item.author, onAuthorClick = { onFacet(FacetType.AUTHOR, it) })
            if (capabilities.hasSeries) {
                item.seriesName?.let { series ->
                    SeriesLine(seriesName = series, seriesId = seriesId, onSeriesClick = onSeriesClick)
                }
            }
            if (item.isListenable && item.audioDurationSec > 0) {
                AudiobookDurationLine(item.audioDurationSec, item.readingProgress)
            }
            PublicationFactsLine(item, estimatedTotalReadingTimeSec, pdfPageCount)
            if (item.readingProgress > 0f) {
                ReadingProgressIndicator(progress = item.readingProgress, listened = item.isListenable && !item.isReadable)
            }
            ActionRow(
                item = item,
                isInToRead = isInToRead,
                capabilities = capabilities,
                downloadState = downloadState,
                isCachedOrDownloaded = isCachedOrDownloaded,
                isOffline = isOffline,
                readaloudDownloadState = readaloudDownloadState,
                audiobookDownloadState = audiobookDownloadState,
                onReadItem = onReadItem,
                onListenItem = onListenItem,
                onMarkAsRead = onMarkAsRead,
                onMarkAsUnread = onMarkAsUnread,
                onToggleToRead = onToggleToRead,
            onAddToPlaylist = onAddToPlaylist,
                onDownload = onDownload,
                onRemove = onRemove,
                onDownloadReadaloud = onDownloadReadaloud,
                onRemoveReadaloud = onRemoveReadaloud,
                onDownloadAudiobook = onDownloadAudiobook,
                onRemoveAudiobook = onRemoveAudiobook,
            )

            // TOC row — EPUB items only; hidden if TOC loaded as empty
            val tocReady = tocState as? TocState.Ready
            if (item.ebookFormat == EbookFormat.Epub && (tocState is TocState.Loading || tocReady?.entries?.isNotEmpty() == true)) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_table_of_contents)) },
                    supportingContent = {
                        when (val s = tocState) {
                            is TocState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                            is TocState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_sections_count, s.entries.size))
                        }
                    },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(
                        enabled = tocReady != null && tocReady.entries.isNotEmpty(),
                        onClick = { showTocSheet = true },
                    ),
                )
            }

            // Chapters row — audiobook items; hidden if chapters loaded as empty
            val chaptersReady = chaptersState as? ChaptersState.Ready
            if (item.isListenable && (chaptersState is ChaptersState.Loading || chaptersReady?.chapters?.isNotEmpty() == true)) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters)) },
                    supportingContent = {
                        when (val s = chaptersState) {
                            is ChaptersState.Loading -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_loading))
                            is ChaptersState.Ready -> Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters_count, s.chapters.size))
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Headphones, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(
                        enabled = chaptersReady != null && chaptersReady.chapters.isNotEmpty(),
                        onClick = { showChaptersSheet = true },
                    ),
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                CollapsibleDescription(desc)
            }
            MetadataLines(item = item, onFacet = onFacet)
            FormatLine(item.ebookFormat, epubVersion)
        }
    }

    if (showTocSheet) {
        val entries = (tocState as? TocState.Ready)?.entries ?: emptyList()
        TocPanel(
            entries = entries,
            activeHref = currentPositionHref,
            onEntryClick = { entry ->
                onReadItemAtHref(item, entry.href)
                showTocSheet = false
            },
            onDismiss = { showTocSheet = false },
        )
    }
    if (showChaptersSheet) {
        val chapters = (chaptersState as? ChaptersState.Ready)?.chapters ?: emptyList()
        ItemChaptersSheet(
            chapters = chapters,
            onChapterClick = { chapter -> onListenItemAtSec(item, chapter.startSec) },
            onDismiss = { showChaptersSheet = false },
        )
    }
}

@Composable
private fun TitleWithReadaloudIndicator(
    title: String,
    hasReadaloud: Boolean,
    onReadaloudClick: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, fill = false))
        if (hasReadaloud) {
            Spacer(modifier = Modifier.width(8.dp))
            // Tapping the badge is the sole entry point to the "Readalouds" Filtered Books Screen
            // (ADR 0033) — self-gating, since the badge only shows when the book has a readaloud.
            Icon(
                painter = painterResource(R.drawable.ic_readaloud),
                contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_show_all_readalouds),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onReadaloudClick),
            )
        }
    }
}

/**
 * The "By …" line. Splits the flattened author string on ", " so each author is an independent,
 * tappable facet leading to that author's Filtered Books Screen (ADR 0033).
 */
@Composable
private fun AuthorByline(author: String, onAuthorClick: (String) -> Unit) {
    if (author.isBlank()) return
    val authors = author.split(", ").filter { it.isNotBlank() }
    ClickableTokenLine(
        prefix = stringResource(R.string.ui_by) + " ",
        tokens = authors,
        style = MaterialTheme.typography.titleLarge,
        onTokenClick = onAuthorClick,
    )
}

/**
 * A "<prefix> a, b, c" line where each comma-separated token is an independently tappable facet,
 * rendered as a single wrapping [Text] with per-token [LinkAnnotation.Clickable] spans (no FlowRow
 * — that API's 1.7 overload is the wrong one to depend on across foundation versions).
 */
@Composable
private fun ClickableTokenLine(
    prefix: String,
    tokens: List<String>,
    style: androidx.compose.ui.text.TextStyle,
    onTokenClick: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = LocalContentColor.current
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))
    val annotated = buildAnnotatedString {
        append(prefix)
        tokens.forEachIndexed { index, token ->
            withLink(
                LinkAnnotation.Clickable(
                    tag = "token",
                    styles = linkStyles,
                    linkInteractionListener = { onTokenClick(token) },
                ),
            ) { append(token) }
            if (index < tokens.lastIndex) append(", ")
        }
    }
    Text(text = annotated, style = style.copy(color = baseColor))
}

/**
 * The series line, e.g. "The Stormlight Archive #1". Tappable through to the existing Series detail
 * when the series id is known; the displayed text keeps the "#<sequence>" suffix but the series
 * lookup is by bare name (the suffix is stripped for navigation).
 */
@Composable
private fun SeriesLine(seriesName: String, seriesId: String?, onSeriesClick: (String, String) -> Unit) {
    val bareName = seriesName.substringBeforeLast(" #").trim()
    if (seriesId != null) {
        FacetValue(
            text = seriesName,
            style = MaterialTheme.typography.bodyLarge,
        ) { onSeriesClick(seriesId, bareName) }
    } else {
        Text(text = seriesName, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A single tappable metadata value rendered in the primary colour. */
@Composable
private fun FacetValue(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(
    item: LibraryItem,
    isInToRead: Boolean,
    capabilities: DetailCapabilities = DetailCapabilities.All,
    downloadState: DownloadState,
    isCachedOrDownloaded: Boolean,
    isOffline: Boolean,
    readaloudDownloadState: DownloadState?,
    audiobookDownloadState: DownloadState? = null,
    onReadItem: (LibraryItem) -> Unit,
    onListenItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onToggleToRead: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDownloadReadaloud: () -> Unit = {},
    onRemoveReadaloud: () -> Unit = {},
    onDownloadAudiobook: () -> Unit = {},
    onRemoveAudiobook: () -> Unit = {},
) {
    // An item may be readable (has an ebook), listenable (an Audiobook — ADR 0035), both (a
    // combined item), or neither. The action row offers Read and Listen independently; only a wholly
    // un-openable item shows the empty-state message. When the active Source lacks
    // AudiobookMediaCapability (e.g. LocalFiles has no audiobook player yet, issue #439), a nominally
    // listenable item is treated as un-openable on the listen side — no Listen button, and if there's
    // also no readable ebook the empty-state message wins.
    val effectivelyListenable = item.isListenable && capabilities.hasAudiobookMedia
    if (!item.isReadable && !effectivelyListenable) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_nothing_to_read_or_listen_to_for_this_item_on_the_source),
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
            // Offline with no local copy: the book can't be fetched, so disable Read with a hint
            // rather than letting the tap fall through to an error screen.
            val readDisabledByOffline = isOffline && !isCachedOrDownloaded
            if (readDisabledByOffline) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_connect_to_download_book)) } },
                    state = rememberTooltipState(),
                    modifier = Modifier.weight(1f),
                ) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_read))
                    }
                }
            } else {
                Button(
                    onClick = { onReadItem(item) },
                    enabled = downloadState !is DownloadState.InProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_read))
                }
            }
        }
        if (item.isListenable && capabilities.hasAudiobookMedia) {
            // The audiobook player resolves download > bundle > ABS stream (ADR 0035), so Listen needs
            // connectivity only when neither a dedicated audiobook download nor a readaloud bundle is
            // present locally — either local source plays offline.
            val audiobookAvailableOffline = audiobookDownloadState == DownloadState.Downloaded ||
                audiobookDownloadState == DownloadState.Cached
            val listenBlockedOffline = isOffline &&
                !audiobookAvailableOffline &&
                readaloudDownloadState != DownloadState.Downloaded
            if (listenBlockedOffline) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_connect_to_stream_audio)) } },
                    state = rememberTooltipState(),
                    modifier = Modifier.weight(1f),
                ) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_listen))
                    }
                }
            } else {
                Button(
                    onClick = { onListenItem(item) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_listen))
                }
            }
        }
        ReadToggleButton(
            isRead = item.readingProgress >= READ_PROGRESS_THRESHOLD,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
        )
        if (capabilities.hasPlaylists) {
            ToReadToggleButton(
                isInToRead = isInToRead,
                onToggle = onToggleToRead,
            )
        }
        if (capabilities.hasAddToPlaylist) {
            AddToPlaylistToggleButton(onClick = onAddToPlaylist)
        }
        // Download affordances are gated on DownloadsCapability — Sources without a local store
        // (LocalFiles today) hide every download button (ebook, audiobook, readaloud bundle).
        val showDownloadAffordances = capabilities.hasDownloads
        // The base DownloadButton manages the ABS EPUB, so it only applies to a readable item. A
        // matched ABS item additionally gets the ReadaloudDownloadButton below, which fetches the
        // Storyteller synced bundle (ADR 0027/ADR 0032) for audio + highlight.
        if (showDownloadAffordances && item.isReadable) {
            DownloadButton(
                state = downloadState,
                onDownload = onDownload,
                onRemove = onRemove,
            )
        }
        // A listenable item gets its own download control: the ABS audiobook tracks for offline play
        // (ADR 0035). Disabled offline when not yet downloaded (can't fetch).
        if (showDownloadAffordances && item.isListenable && audiobookDownloadState != null) {
            val audioOfflineBlocked = isOffline && audiobookDownloadState == DownloadState.NotDownloaded
            val audioButton: @Composable () -> Unit = {
                DownloadButton(
                    state = audiobookDownloadState,
                    onDownload = onDownloadAudiobook,
                    onRemove = onRemoveAudiobook,
                    enabled = !audioOfflineBlocked,
                )
            }
            if (audioOfflineBlocked) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_connect_to_download_audiobook)) } },
                    state = rememberTooltipState(),
                ) { audioButton() }
            } else {
                audioButton()
            }
        }
        if (capabilities.hasReadaloud && readaloudDownloadState != null) {
            val readaloudOfflineBlocked = isOffline && readaloudDownloadState == DownloadState.NotDownloaded
            val readaloudButton: @Composable () -> Unit = {
                ReadaloudDownloadButton(
                    state = readaloudDownloadState,
                    onDownload = onDownloadReadaloud,
                    onRemove = onRemoveReadaloud,
                    enabled = !readaloudOfflineBlocked,
                )
            }
            if (readaloudOfflineBlocked) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_connect_to_download_readaloud_audio)) } },
                    state = rememberTooltipState(),
                ) { readaloudButton() }
            } else {
                readaloudButton()
            }
        }
    }
}

@Composable
private fun ReadingProgressIndicator(progress: Float, listened: Boolean = false) {
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(
                if (listened) com.riffle.app.R.string.ui_progress_listened else com.riffle.app.R.string.ui_progress_read,
                (progress * 100).toInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MetadataLines(item: LibraryItem, onFacet: (FacetType, String) -> Unit) {
    val hasAny = item.publishedYear != null || item.genres.isNotEmpty() ||
        !item.language.isNullOrBlank() || item.publisher != null
    if (!hasAny) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.publishedYear?.let { year ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_published), style = MaterialTheme.typography.bodyMedium)
                FacetValue(text = year) { onFacet(FacetType.YEAR, year) }
            }
        }
        if (item.genres.isNotEmpty()) {
            ClickableTokenLine(
                prefix = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_genres),
                tokens = item.genres,
                style = MaterialTheme.typography.bodyMedium,
                onTokenClick = { onFacet(FacetType.GENRE, it) },
            )
        }
        item.language?.takeIf { it.isNotBlank() }?.let { language ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_language), style = MaterialTheme.typography.bodyMedium)
                FacetValue(text = language) { onFacet(FacetType.LANGUAGE, language) }
            }
        }
        item.publisher?.let { publisher ->
            Text(text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_publisher, publisher), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
