package com.riffle.shared.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.CoverImage
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

private val TitleStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
private val AuthorStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF666666))
private val MetadataStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF6650A4))
private val ButtonTextStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    color = Color.White,
)

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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.forEach { value ->
            BasicText(
                text = value,
                style = style,
                modifier = Modifier
                    .testTag("facet-$value")
                    .clickable { onClick(value) },
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text = "Loading…", style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)))
    }
}

@Composable
private fun ErrorContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "Item not found",
                style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)),
            )
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
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
@Composable
internal fun ReadyContent(
    state: LibraryItemDetailUiState.Ready,
    token: String,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleToRead: () -> Unit,
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
    RiffleMessageScaffold(messages) {
        ReadyBody(
            state = state,
            token = token,
            onBack = onBack,
            onRead = onRead,
            onToggleToRead = onToggleToRead,
            downloadControls = downloadControls,
            onAddToPlaylist = onAddToPlaylist,
            onFacet = onFacet,
        )
    }
}

@Composable
private fun ReadyBody(
    state: LibraryItemDetailUiState.Ready,
    token: String,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleToRead: () -> Unit,
    downloadControls: @Composable () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onFacet: (FacetType, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .align(Alignment.CenterHorizontally),
        ) {
            // The hero was the procedural placeholder even when the item had artwork —
            // `shared/commonMain` had no image loader call site at all.
            CoverImage(
                url = state.item.coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = state.item.isAudiobookOnly,
                instrumentationKind = "detail",
                instrumentationKey = state.item.id,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        BasicText(
            text = state.item.title,
            style = TitleStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // The byline is a facet drill-in, exactly as Android's `AuthorByline` is: tapping it
        // lists every book by that author. Android splits a multi-author string on ", " when it
        // matches (`facetMatches`), so each name is offered separately here too.
        FacetRow(
            values = state.item.author.split(", ").filter { it.isNotBlank() },
            style = AuthorStyle,
            onClick = { onFacet(FacetType.AUTHOR, it) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Genre / year / language chips — the same four facets Android's `MetadataLines` offers.
        // Without them `FilteredBooksViewModel` has no iOS entry point at all (#1072 §1).
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

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF6650A4))
                    .clickable(onClick = onRead),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(text = "Read", style = ButtonTextStyle)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.isInToRead) Color(0xFF6650A4) else Color(0xFFEAE0F8))
                    .clickable(onClick = onToggleToRead),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = if (state.isInToRead) "In To-Read" else "Add to To-Read",
                    style = ButtonTextStyle.copy(
                        color = if (state.isInToRead) Color.White else Color(0xFF6650A4),
                    ),
                )
            }
        }

        if (onAddToPlaylist != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEAE0F8))
                    .testTag("detail-add-to-playlist")
                    .clickable(onClick = onAddToPlaylist),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = PlaylistLabels.English.addToPlaylist,
                    style = ButtonTextStyle.copy(color = Color(0xFF6650A4)),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        downloadControls()

        if (state.isOffline) {
            Spacer(modifier = Modifier.height(12.dp))
            BasicText(
                text = "You are offline",
                style = TextStyle(fontSize = 13.sp, color = Color(0xFFAA8800)),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
