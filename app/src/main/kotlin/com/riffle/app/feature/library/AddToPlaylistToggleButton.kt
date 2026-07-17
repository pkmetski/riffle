package com.riffle.app.feature.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Circular "Add to playlist" affordance shown next to [ToReadToggleButton] on the item detail
 * action row for audiobook items on a Source with [com.riffle.core.catalog.PlaylistsCapability].
 *
 * Design constraints (learned by shipping the wrong thing first):
 * - Same 40dp outlined-circle chrome as [ToReadToggleButton] — a plain [androidx.compose.material3.IconButton]
 *   next to that toggle read as a darker, chrome-less sibling.
 * - Icon MUST match the Playlists tab (also QueueMusic) so the two surfaces share visual
 *   language — the button and the tab represent the same concept. The distinction from the
 *   sibling [ToReadToggleButton] (PlaylistAddCheck: lines + check) comes from the glyph itself:
 *   QueueMusic is lines + music note, which reads as "audio list" rather than "wishlist".
 * - Stateless — unlike ToRead, membership is per-playlist and lives in the sheet, not on the row.
 */
@Composable
fun AddToPlaylistToggleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "Add to playlist",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}
