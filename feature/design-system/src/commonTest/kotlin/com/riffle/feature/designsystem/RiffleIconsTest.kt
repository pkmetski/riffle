package com.riffle.feature.designsystem

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The iOS half of the icon-registry guardrail. `app/src/test/.../RiffleIconsTest` pins each
 * concept to the androidx Material vector it must draw; that test cannot run on iOS because
 * `androidx.compose.material.icons` is Android-only, so these pin the same claims against the
 * shared registry itself and execute on `iosSimulatorArm64Test`.
 *
 * What they are guarding: `SharedUiIcons` (the object this replaces) rendered **To-Read with the
 * Bookmarks glyph Android reserves for Annotations**, and Annotations with a Star that exists
 * nowhere in Android's design system. Nothing failed — both platforms compiled and both suites
 * were green while the two apps drew different icons for the same two concepts.
 */
class RiffleIconsTest {

    @Test
    fun toReadIsPlaylistAddNotTheBookmarkFamily() {
        assertEquals("PlaylistAdd", RiffleIcons.ToRead.name)
        assertSameArtwork(RiffleIcons.PlaylistAdd, RiffleIcons.ToRead)
        assertNotEquals(pathNodes(RiffleIcons.Bookmarks), pathNodes(RiffleIcons.ToRead))
    }

    @Test
    fun toReadFilledIsPlaylistAddCheck() {
        assertEquals("PlaylistAddCheck", RiffleIcons.ToReadFilled.name)
        assertSameArtwork(RiffleIcons.PlaylistAddCheck, RiffleIcons.ToReadFilled)
    }

    @Test
    fun annotationsOwnsTheBookmarkFamily() {
        assertEquals("Bookmarks", RiffleIcons.Annotations.name)
        assertSameArtwork(RiffleIcons.Bookmarks, RiffleIcons.Annotations)
    }

    @Test
    fun toReadAndAnnotationsAreDistinctGlyphs() {
        assertNotEquals(pathNodes(RiffleIcons.ToRead), pathNodes(RiffleIcons.Annotations))
        assertNotEquals(pathNodes(RiffleIcons.ToReadFilled), pathNodes(RiffleIcons.Annotations))
    }

    @Test
    fun directionalGlyphsAreMirroredInRtl() {
        // SharedUiIcons' builder had no autoMirror parameter at all, so every one of these was
        // drawn left-to-right in a right-to-left layout.
        listOf(
            RiffleIcons.ArrowBack,
            RiffleIcons.QueueMusic,
            RiffleIcons.FormatListNumbered,
            RiffleIcons.PlaylistAdd,
            RiffleIcons.PlaylistAddCheck,
        ).forEach { assertTrue(it.autoMirror, "${it.name} must be auto-mirrored in RTL") }
    }

    @Test
    fun nonDirectionalGlyphsAreNotMirrored() {
        listOf(RiffleIcons.Home, RiffleIcons.Bookmarks, RiffleIcons.Search, RiffleIcons.GridView)
            .forEach { assertTrue(!it.autoMirror, "${it.name} must not be auto-mirrored") }
    }

    @Test
    fun everyGlyphIsTwentyFourDpOnATwentyFourUnitViewport() {
        listOf(
            RiffleIcons.ArrowBack, RiffleIcons.ArrowDropDown, RiffleIcons.Bookmarks,
            RiffleIcons.Check, RiffleIcons.CheckCircle, RiffleIcons.Close, RiffleIcons.Folder,
            RiffleIcons.FormatListNumbered, RiffleIcons.GridView, RiffleIcons.Headphones,
            RiffleIcons.Home, RiffleIcons.Menu, RiffleIcons.PlaylistAdd,
            RiffleIcons.PlaylistAddCheck, RiffleIcons.QueueMusic, RiffleIcons.Schedule,
            RiffleIcons.Search, RiffleIcons.Warning,
        ).forEach {
            assertEquals(24f, it.viewportWidth, "${it.name} viewportWidth")
            assertEquals(24f, it.viewportHeight, "${it.name} viewportHeight")
            assertTrue(pathNodes(it).isNotEmpty(), "${it.name} has no path data")
        }
    }

    private fun assertSameArtwork(expected: ImageVector, actual: ImageVector) {
        assertEquals(pathNodes(expected), pathNodes(actual))
        assertEquals(expected.autoMirror, actual.autoMirror)
    }

    private fun pathNodes(vector: ImageVector): List<PathNode> = flatten(vector.root)

    private fun flatten(node: VectorNode): List<PathNode> = when (node) {
        is VectorPath -> node.pathData
        is VectorGroup -> node.flatMap { flatten(it) }
    }
}
