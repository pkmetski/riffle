package com.riffle.app.feature.reader

import com.riffle.core.models.ReadaloudLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsTargetResolverTest {

    private fun link(itemId: String) = ReadaloudLink(
        storytellerSourceId = "st", storytellerBookId = "b",
        absSourceId = "abs", absLibraryItemId = itemId, userConfirmed = false,
    )

    @Test
    fun `combined item is both the ebook and the audio target`() {
        val items = listOf(AbsLinkMedia(link("combined"), isReadable = true, hasAudio = true))

        val resolved = resolveAbsTargets(openedItemId = "combined", items = items)

        assertEquals("combined", resolved.ebook?.absLibraryItemId)
        assertEquals("combined", resolved.audio?.absLibraryItemId)
    }

    @Test
    fun `split libraries resolve the ebook item and the audiobook item separately`() {
        val items = listOf(
            AbsLinkMedia(link("ebook"), isReadable = true, hasAudio = false),
            AbsLinkMedia(link("audiobook"), isReadable = false, hasAudio = true),
        )

        val resolved = resolveAbsTargets(openedItemId = "ebook", items = items)

        assertEquals("ebook", resolved.ebook?.absLibraryItemId)
        assertEquals("audiobook", resolved.audio?.absLibraryItemId)
    }

    @Test
    fun `the opened item is preferred as the ebook target when several have ebooks`() {
        val items = listOf(
            AbsLinkMedia(link("other-ebook"), isReadable = true, hasAudio = false),
            AbsLinkMedia(link("opened-ebook"), isReadable = true, hasAudio = false),
        )

        val resolved = resolveAbsTargets(openedItemId = "opened-ebook", items = items)

        assertEquals("opened-ebook", resolved.ebook?.absLibraryItemId)
    }

    @Test
    fun `an ebook-only match has no audio target`() {
        val items = listOf(AbsLinkMedia(link("ebook"), isReadable = true, hasAudio = false))

        val resolved = resolveAbsTargets(openedItemId = "ebook", items = items)

        assertEquals("ebook", resolved.ebook?.absLibraryItemId)
        assertNull(resolved.audio)
    }
}
