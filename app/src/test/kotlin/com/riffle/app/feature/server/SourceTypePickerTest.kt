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
    fun `cards are ordered ABS first then LocalFiles then Chitanka`() {
        val cards = sourceTypeCards()
        assertEquals(3, cards.size)
        assertEquals(SourceTypeChoice.Audiobookshelf, cards[0].type)
        assertEquals(SourceTypeChoice.LocalFiles, cards[1].type)
        assertEquals(SourceTypeChoice.Chitanka, cards[2].type)
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
}
