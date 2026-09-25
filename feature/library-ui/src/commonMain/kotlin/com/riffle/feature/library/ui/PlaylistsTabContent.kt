package com.riffle.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.models.CatalogPlaylist
import com.riffle.feature.designsystem.TestTags

/**
 * Playlists tab body, rendered by both hosts. Shown only on ABS audiobook roots (gated by
 * [com.riffle.feature.library.LibraryItemsViewModel.tabVisibility]). The "To Read" playlist is
 * already filtered out by [com.riffle.core.domain.PlaylistsRepository], so this composable renders
 * whatever it receives verbatim.
 *
 * [onPlaylistSelected] is what makes the tab a drill-in rather than a dead list — iOS's copy of
 * this tab used to render names with no navigation at all because it had no
 * `PlaylistDetailScreen` to open (#1072 §1).
 */
@Composable
fun PlaylistsTabContent(
    playlists: List<CatalogPlaylist>,
    labels: PlaylistLabels,
    onPlaylistSelected: (CatalogPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(labels.noPlaylistsFromAnyItem)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(playlist = playlist, labels = labels, onClick = { onPlaylistSelected(playlist) })
        }
    }
}

@Composable
internal fun PlaylistRow(
    playlist: CatalogPlaylist,
    labels: PlaylistLabels,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.playlistRow(playlist.id))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LibraryUiGlyphs.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlistItemCountLabel(playlist.bookCount, labels),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = LibraryUiGlyphs.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
