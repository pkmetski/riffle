package com.riffle.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Central icon registry. Keeps a single glyph per concept so the same feature reads the same
 * everywhere it surfaces. Route every icon that represents one of these concepts through here;
 * never re-import the underlying Material symbol at a call site.
 *
 * - [ToRead] / [ToReadFilled]: the reading-queue / "save for later" concept. Used by the
 *   details-screen toggle and the library nav tab.
 * - [Annotations]: highlights + notes + reader-level bookmarks. Used by the reader's annotations
 *   panel button and (future) the main-screen Annotations view. This concept owns the
 *   `Bookmark*` family; do not use it for anything else.
 */
object RiffleIcons {
    val ToRead: ImageVector = Icons.AutoMirrored.Outlined.PlaylistAdd
    val ToReadFilled: ImageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck
    val Annotations: ImageVector = Icons.Filled.Bookmarks
}
