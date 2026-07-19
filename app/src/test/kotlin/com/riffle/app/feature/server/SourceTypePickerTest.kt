package com.riffle.app.feature.server

import com.riffle.core.models.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SourceType picker's data model. Post-ADR-0044 the cards are derived by iterating
 * `WebSourceDescriptors.all` and hiding `descriptor.isSingleton` types whose entry is present in
 * the `installedTypes` set. Card type is the concrete [SourceType] enum entry; the picker screen
 * routes `onPick(type)` to the correct add-route.
 */
class SourceTypePickerTest {

    @Test
    fun `cards are ordered ABS LocalFiles Chitanka Gutenberg Komga`() {
        val cards = sourceTypeCards()
        assertEquals(5, cards.size)
        assertEquals(SourceType.ABS, cards[0].type)
        assertEquals(SourceType.LOCAL_FILES, cards[1].type)
        assertEquals(SourceType.CHITANKA, cards[2].type)
        assertEquals(SourceType.GUTENBERG, cards[3].type)
        assertEquals(SourceType.KOMGA, cards[4].type)
    }

    @Test
    fun `Gutenberg card is enabled and not coming soon`() {
        val gb = sourceTypeCards().first { it.type == SourceType.GUTENBERG }
        assertTrue(gb.enabled)
        assertFalse(gb.comingSoon)
        assertEquals("Project Gutenberg", gb.title)
    }

    // Same singleton rationale as Chitanka — a credential-less public catalogue has nothing to
    // disambiguate a second row.
    @Test
    fun `Gutenberg card is hidden once a Gutenberg source exists`() {
        val cards = sourceTypeCards(installedTypes = setOf(SourceType.GUTENBERG))
        assertTrue(cards.none { it.type == SourceType.GUTENBERG })
        assertTrue(cards.any { it.type == SourceType.ABS })
    }

    @Test
    fun `Chitanka card is enabled and not coming soon`() {
        val ch = sourceTypeCards().first { it.type == SourceType.CHITANKA }
        assertTrue(ch.enabled)
        assertFalse(ch.comingSoon)
        assertEquals("Chitanka", ch.title)
    }

    @Test
    fun `ABS card is enabled and not coming soon`() {
        val abs = sourceTypeCards().first { it.type == SourceType.ABS }
        assertTrue(abs.enabled)
        assertFalse(abs.comingSoon)
        assertEquals("Audiobookshelf", abs.title)
    }

    @Test
    fun `LocalFiles card is enabled`() {
        val lf = sourceTypeCards().first { it.type == SourceType.LOCAL_FILES }
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
        val cards = sourceTypeCards(installedTypes = setOf(SourceType.LOCAL_FILES))
        assertTrue(cards.none { it.type == SourceType.LOCAL_FILES })
        assertTrue(cards.any { it.type == SourceType.ABS })
    }

    // Chitanka is a device singleton — no credentials to disambiguate a second row from the
    // first, so a duplicate would be either a silent no-op or a confusing duplicate library
    // entry. If this predicate ever flips, the picker will start offering to add duplicates.
    @Test
    fun `Chitanka card is hidden once a Chitanka source exists`() {
        val cards = sourceTypeCards(installedTypes = setOf(SourceType.CHITANKA))
        assertTrue(cards.none { it.type == SourceType.CHITANKA })
        assertTrue(cards.any { it.type == SourceType.ABS })
        assertTrue(cards.any { it.type == SourceType.LOCAL_FILES })
    }

    // ABS is not a singleton — a second ABS server is a first-class use case. Even with an ABS
    // source installed, the picker still shows the ABS card.
    @Test
    fun `ABS card is shown even when an ABS source exists (multi-server)`() {
        val cards = sourceTypeCards(installedTypes = setOf(SourceType.ABS))
        assertTrue(cards.any { it.type == SourceType.ABS })
    }

    // All credential-less singletons already installed: only ABS + Komga (both multi-instance,
    // credentialed) remain addable.
    @Test
    fun `only ABS and Komga remain when all credential-less singletons are installed`() {
        val cards = sourceTypeCards(
            installedTypes = setOf(
                SourceType.LOCAL_FILES,
                SourceType.CHITANKA,
                SourceType.GUTENBERG,
            ),
        )
        assertEquals(2, cards.size)
        assertEquals(SourceType.ABS, cards[0].type)
        assertEquals(SourceType.KOMGA, cards[1].type)
    }
}
