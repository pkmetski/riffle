package com.riffle.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.riffle.feature.designsystem.RiffleIcons
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These pin each central icon to the chosen Material vector. A regression that "just re-imports
 * Bookmark at a call site" or silently swaps ToRead back to the Bookmark family flips one of
 * these red, which is the point — the central object exists to prevent drift, and the test
 * exists to prevent the central object from silently drifting.
 *
 * The registry moved to `:feature:design-system` so iOS shares it (it had inverted To-Read and
 * Annotations against Android). `androidx.compose.material.icons` is Android-only and cannot be
 * referenced from a module with iOS targets, so the shared registry redraws the same Material
 * path data by hand. The assertion is therefore no longer `assertSame` against the androidx
 * instance but equality of the vectors' **path geometry** — which pins strictly more than the
 * original did: it would also catch the shared glyph being redrawn as something else while still
 * being named `PlaylistAdd`.
 */
class RiffleIconsTest {

    @Test
    fun toReadOutlineIsPlaylistAdd() {
        assertSameArtwork(Icons.AutoMirrored.Outlined.PlaylistAdd, RiffleIcons.ToRead)
    }

    @Test
    fun toReadFilledIsPlaylistAddCheck() {
        assertSameArtwork(Icons.AutoMirrored.Filled.PlaylistAddCheck, RiffleIcons.ToReadFilled)
    }

    @Test
    fun annotationsIsBookmarks() {
        // Bookmark* family belongs to the Annotations concept — reader panel button and the future
        // main-screen Annotations view. If this flips, the To-Read / Annotations separation is
        // being re-collided.
        assertSameArtwork(Icons.Filled.Bookmarks, RiffleIcons.Annotations)
    }

    private fun assertSameArtwork(expected: ImageVector, actual: ImageVector) {
        assertEquals(pathNodes(expected), pathNodes(actual))
        assertEquals(expected.autoMirror, actual.autoMirror)
        assertEquals(expected.viewportWidth, actual.viewportWidth, 0f)
        assertEquals(expected.viewportHeight, actual.viewportHeight, 0f)
    }

    private fun pathNodes(vector: ImageVector): List<PathNode> = flatten(vector.root)

    private fun flatten(node: VectorNode): List<PathNode> = when (node) {
        is VectorPath -> node.pathData
        is VectorGroup -> node.flatMap { flatten(it) }
    }
}
