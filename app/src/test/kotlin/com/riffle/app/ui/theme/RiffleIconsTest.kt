package com.riffle.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmarks
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * These pin each central icon to the chosen Material vector. A regression that "just re-imports
 * Bookmark at a call site" or silently swaps ToRead back to the Bookmark family flips one of
 * these red, which is the point — the central object exists to prevent drift, and the test
 * exists to prevent the central object from silently drifting.
 */
class RiffleIconsTest {

    @Test
    fun toReadOutlineIsPlaylistAdd() {
        assertSame(Icons.AutoMirrored.Outlined.PlaylistAdd, RiffleIcons.ToRead)
    }

    @Test
    fun toReadFilledIsPlaylistAddCheck() {
        assertSame(Icons.AutoMirrored.Filled.PlaylistAddCheck, RiffleIcons.ToReadFilled)
    }

    @Test
    fun annotationsIsBookmarks() {
        // Bookmark* family belongs to the Annotations concept — reader panel button and the future
        // main-screen Annotations view. If this flips, the To-Read / Annotations separation is
        // being re-collided.
        assertSame(Icons.Filled.Bookmarks, RiffleIcons.Annotations)
    }
}
