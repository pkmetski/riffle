package com.riffle.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.models.CatalogPlaylist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Bottom sheet for adding or removing the current item from playlists on the active Source,
 * rendered by both hosts. State is driven by callers: [playlistsFlow] holds the current,
 * "To Read"-filtered set; tapping a row calls [onToggle]; "+ New playlist" opens a small dialog
 * that calls [onCreate] and closes on "" (empty string = success) or displays whatever error
 * string it returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    itemId: String,
    playlistsFlow: Flow<List<CatalogPlaylist>>,
    labels: PlaylistLabels,
    onToggle: (CatalogPlaylist) -> Unit,
    onCreate: suspend (name: String) -> String,
    onDismiss: () -> Unit,
) {
    val playlists by playlistsFlow.collectAsState(initial = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                labels.addToPlaylist,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist-new")
                    .clickable { showCreateDialog = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(LibraryUiGlyphs.Add, contentDescription = null)
                Text(labels.newPlaylist, style = MaterialTheme.typography.bodyLarge)
            }
            if (playlists.isEmpty()) {
                Text(
                    labels.noPlaylistsGetStarted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        PickerRow(
                            playlist = playlist,
                            labels = labels,
                            isSelected = itemId in playlist.itemIds,
                            onToggle = { onToggle(playlist) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        NewPlaylistDialog(
            labels = labels,
            onDismiss = { showCreateDialog = false },
            onCreate = onCreate,
            onSuccess = { showCreateDialog = false },
        )
    }
}

@Composable
private fun PickerRow(
    playlist: CatalogPlaylist,
    labels: PlaylistLabels,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist-pick-${playlist.id}")
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isSelected) {
                Icon(
                    LibraryUiGlyphs.Check,
                    contentDescription = labels.inThisPlaylist,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlistItemCountLabel(playlist.bookCount, labels),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewPlaylistDialog(
    labels: PlaylistLabels,
    onDismiss: () -> Unit,
    onCreate: suspend (name: String) -> String,
    onSuccess: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text(labels.newPlaylist) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (error != null) error = null
                    },
                    label = { Text(labels.name) },
                    singleLine = true,
                    enabled = !isCreating,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().testTag("playlist-name-field"),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isCreating) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isCreating && name.isNotBlank(),
                onClick = {
                    isCreating = true
                    scope.launch {
                        val err = onCreate(name)
                        isCreating = false
                        if (err.isEmpty()) onSuccess() else error = err
                    }
                },
            ) { Text(labels.create) }
        },
        dismissButton = {
            TextButton(enabled = !isCreating, onClick = onDismiss) { Text(labels.cancel) }
        },
    )
}
