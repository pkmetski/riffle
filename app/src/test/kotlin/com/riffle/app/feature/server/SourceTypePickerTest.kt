package com.riffle.app.feature.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SourceType picker's data model (#435, #438). Both source types are enabled once
 * the LocalFiles Catalog and Add-Source flow have landed (#438). If any of these assertions
 * flip, the picker's contract has changed and the change must be intentional.
 */
class SourceTypePickerTest {

    @Test
    fun `cards are ordered ABS first then LocalFiles then Chitanka then Gutenberg`() {
        val cards = sourceTypeCards()
        assertEquals(4, cards.size)
        assertEquals(SourceTypeChoice.Audiobookshelf, cards[0].type)
        assertEquals(SourceTypeChoice.LocalFiles, cards[1].type)
        assertEquals(SourceTypeChoice.Chitanka, cards[2].type)
        assertEquals(SourceTypeChoice.Gutenberg, cards[3].type)
    }

    @Test
    fun `Gutenberg card is enabled and not coming soon`() {
        val gb = sourceTypeCards().first { it.type is SourceTypeChoice.Gutenberg }
        assertTrue(gb.enabled)
        assertFalse(gb.comingSoon)
        assertEquals("Project Gutenberg", gb.title)
    }

    // Same singleton rationale as Chitanka — a credential-less public catalogue has nothing to
    // disambiguate a second row.
    @Test
    fun `Gutenberg card is hidden once a Gutenberg source exists`() {
        val cards = sourceTypeCards(hasGutenbergSource = true)
        assertTrue(cards.none { it.type is SourceTypeChoice.Gutenberg })
        assertTrue(cards.any { it.type is SourceTypeChoice.Audiobookshelf })
    }

    @Test
    fun `Chitanka card is enabled and not coming soon`() {
        val ch = sourceTypeCards().first { it.type is SourceTypeChoice.Chitanka }
        assertTrue(ch.enabled)
        assertFalse(ch.comingSoon)
        assertEquals("Chitanka", ch.title)
    }

    @Test
    fun `ABS card is enabled and not coming soon`() {
        val abs = sourceTypeCards().first { it.type is SourceTypeChoice.Audiobookshelf }
        assertTrue(abs.enabled)
        assertFalse(abs.comingSoon)
        assertEquals("Audiobookshelf", abs.title)
    }

    @Test
    fun `LocalFiles card is enabled once 438 has landed`() {
        val lf = sourceTypeCards().first { it.type is SourceTypeChoice.LocalFiles }
        assertTrue(lf.enabled)
        assertFalse(lf.comingSoon)
        assertEquals("Local files", lf.title)
    }

    // LocalFiles is a device singleton — once installed, the Add-Source picker must not offer
    // to "add" a second one. Adding another folder to the existing source is a dedicated action
    // in Settings. If this predicate ever flips, the picker will start creating duplicate rows
    // (or silently no-op'ing) instead of guiding the user to the right entry point.
    @Test
    fun `LocalFiles card is hidden once a LocalFiles source exists`() {
        val cards = sourceTypeCards(hasLocalFilesSource = true)
        // ABS + Chitanka remain; the LocalFiles card is omitted because a device already has
        // its (singleton) LocalFiles source and "add another folder" lives in Settings.
        assertTrue(cards.none { it.type is SourceTypeChoice.LocalFiles })
        assertTrue(cards.any { it.type is SourceTypeChoice.Audiobookshelf })
    }

    // Chitanka is a device singleton — no credentials to disambiguate a second row from the
    // first, so a duplicate would be either a silent no-op or a confusing duplicate library
    // entry. If this predicate ever flips, the picker will start offering to add duplicates.
    @Test
    fun `Chitanka card is hidden once a Chitanka source exists`() {
        val cards = sourceTypeCards(hasChitankaSource = true)
        assertTrue(cards.none { it.type is SourceTypeChoice.Chitanka })
        assertTrue(cards.any { it.type is SourceTypeChoice.Audiobookshelf })
        assertTrue(cards.any { it.type is SourceTypeChoice.LocalFiles })
    }

    // All credential-less singletons already installed: only ABS remains addable.
    @Test
    fun `only ABS remains when all credential-less singletons are installed`() {
        val cards = sourceTypeCards(
            hasLocalFilesSource = true,
            hasChitankaSource = true,
            hasGutenbergSource = true,
        )
        assertEquals(1, cards.size)
        assertEquals(SourceTypeChoice.Audiobookshelf, cards[0].type)
    }
}
