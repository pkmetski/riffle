package com.riffle.app.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The expanded-server "Enabled libraries" list. Each row carries up/down controls that move the
 * library within the server's order, plus the visibility switch on the trailing edge.
 *
 * Reordering uses explicit move buttons rather than a drag gesture: this list lives inside the
 * Settings vertical scroll, where a long-press-drag competes with the page scroll and drops are
 * lost. A tap can't be stolen by the scroll, so each move reliably persists. On a move we hand the
 * full new id order to [onReorder]; the displayed order then follows [items] from the ViewModel.
 */
@Composable
internal fun ReorderableLibraryList(
    items: List<LibraryUiItem>,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorder: (orderedLibraryIds: List<String>) -> Unit,
) {
    val transparentColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    items.forEachIndexed { index, item ->
        ListItem(
            colors = transparentColors,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
            headlineContent = { Text(item.library.name) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.size > 1) {
                        IconButton(
                            onClick = { onReorder(items.idsWithSwap(index, index - 1)) },
                            enabled = index > 0,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Move ${item.library.name} up",
                            )
                        }
                        IconButton(
                            onClick = { onReorder(items.idsWithSwap(index, index + 1)) },
                            enabled = index < items.lastIndex,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Move ${item.library.name} down",
                            )
                        }
                    }
                    Switch(
                        checked = item.isVisible,
                        onCheckedChange = { visible -> onSetLibraryVisible(item.library.id, visible) },
                        enabled = item.switchEnabled,
                    )
                }
            },
        )
    }
}

/** The list's library ids with positions [i] and [j] swapped — the new full order to persist. */
private fun List<LibraryUiItem>.idsWithSwap(i: Int, j: Int): List<String> {
    val ids = map { it.library.id }.toMutableList()
    val tmp = ids[i]
    ids[i] = ids[j]
    ids[j] = tmp
    return ids
}
