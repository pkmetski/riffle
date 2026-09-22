package com.riffle.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.designsystem.DefaultCoverPlaceholder

/**
 * A playlist's contents, rendered by both hosts.
 *
 * [itemContent] is the one genuinely host-specific part: `:app` supplies its `LibraryItemCard`
 * (authenticated cover art through its OkHttp Coil loader) and `:shared` supplies
 * [PlaylistItemRow]. Everything else — the play affordance, the per-row remove button, the
 * auto-pop when the Source deletes an emptied playlist and the snackbar wiring — is one
 * implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    labels: PlaylistLabels,
    onNavigateBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem) -> Unit,
    itemContent: @Composable (item: LibraryItem, token: String, onClick: () -> Unit) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }
    // Auto-pop when the playlist is gone (ABS auto-deletes emptied playlists). Without this
    // the screen sits on the empty state forever after the user removes the last item.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onNavigateBack()
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = labels.back)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(labels.loading)
            }
            return@Scaffold
        }
        if (state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(labels.playlistIsEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Play affordance at the top of the list. Tap starts the first item in the audiobook
            // player; the host carries the playlist context into the player so a finished book
            // auto-advances to the next one.
            item(key = "__play_header") {
                Button(
                    onClick = { state.items.firstOrNull()?.let(onPlayItem) },
                    modifier = Modifier.fillMaxWidth().testTag("playlist-play"),
                ) {
                    Icon(LibraryUiGlyphs.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(labels.play)
                }
                Spacer(Modifier.height(4.dp))
            }
            items(state.items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(item, viewModel.authToken) { onItemSelected(item) }
                    }
                    IconButton(
                        onClick = { viewModel.removeItem(item.id) },
                        modifier = Modifier.testTag("playlist-remove-${item.id}"),
                    ) {
                        Icon(
                            LibraryUiGlyphs.RemoveCircleOutline,
                            contentDescription = labels.removeFromPlaylist,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The default playlist row: cover placeholder, title and author.
 *
 * `:shared` passes this as [PlaylistDetailScreen]'s `itemContent`. `:app` passes its own
 * `LibraryItemCard` instead, which additionally fetches the authenticated cover through its
 * OkHttp-backed Coil loader.
 */
@Composable
fun PlaylistItemRow(item: LibraryItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist-item-${item.id}")
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = item.isAudiobookOnly,
            modifier = Modifier.size(width = 40.dp, height = 56.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.author.isNotEmpty()) {
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
