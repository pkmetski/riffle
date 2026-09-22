package com.riffle.feature.library.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1072 §1 — the playlist count line existed twice: Android's `pluralItems` ("1 item" / "4
 * items") and iOS's inline `"${playlist.bookCount} book(s)"`. Both compiled, both suites were
 * green, and the two platforms printed different words for the same number.
 *
 * Reverting [playlistItemCountLabel] to either copy — dropping the singular branch, or going back
 * to "book(s)" — fails the assertions below.
 */
class PlaylistLabelsTest {

    @Test
    fun oneItemUsesTheSingularForm() {
        assertEquals("1 item", playlistItemCountLabel(1, PlaylistLabels.English))
    }

    @Test
    fun otherCountsExpandThePositionalTemplate() {
        assertEquals("0 items", playlistItemCountLabel(0, PlaylistLabels.English))
        assertEquals("4 items", playlistItemCountLabel(4, PlaylistLabels.English))
    }

    /**
     * The plural template is an Android positional template and must be expanded by the shared
     * `formatTemplate`, never left raw — a screen printing "%1${'$'}d items" is the failure mode
     * this pins.
     */
    @Test
    fun theExpandedPluralNeverLeaksTheTemplateToken() {
        val label = playlistItemCountLabel(12, PlaylistLabels.English)

        assertEquals(false, label.contains('%'), "template token leaked into the rendered label")
        assertEquals("12 items", label)
    }
}
