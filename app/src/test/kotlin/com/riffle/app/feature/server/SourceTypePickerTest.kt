package com.riffle.app.feature.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SourceType picker's data model (#435). LocalFiles must stay disabled with a
 * "Coming soon" flag until #7 lands. If any of these assertions flip, the picker's
 * contract has changed and the change must be intentional.
 */
class SourceTypePickerTest {

    @Test
    fun `cards are ordered ABS first then LocalFiles`() {
        val cards = sourceTypeCards()
        assertEquals(2, cards.size)
        assertEquals(SourceTypeChoice.Audiobookshelf, cards[0].type)
        assertEquals(SourceTypeChoice.LocalFiles, cards[1].type)
    }

    @Test
    fun `ABS card is enabled and not coming soon`() {
        val abs = sourceTypeCards().first { it.type is SourceTypeChoice.Audiobookshelf }
        assertTrue(abs.enabled)
        assertFalse(abs.comingSoon)
        assertEquals("Audiobookshelf", abs.title)
    }

    @Test
    fun `LocalFiles card is disabled and coming soon`() {
        val lf = sourceTypeCards().first { it.type is SourceTypeChoice.LocalFiles }
        assertFalse(lf.enabled)
        assertTrue(lf.comingSoon)
        assertEquals("Local files", lf.title)
    }
}
