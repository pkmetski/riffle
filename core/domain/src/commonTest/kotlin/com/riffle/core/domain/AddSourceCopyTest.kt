package com.riffle.core.domain

import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the ABS-variant and Storyteller AddSource copy after the descriptor-driven refactor.
 * These strings now come from [com.riffle.core.domain.AbsWebSourceDescriptor.addSourceCopyFor];
 * flipping any of them means the descriptor was retitled. Storyteller strings are pinned
 * untouched — Storyteller's rename is #441's job. WebDAV is a Service (no descriptor), so its
 * strings live inline in AddSourceScreen and aren't covered here.
 */
class AddSourceCopyTest {
    private val absDescriptor = WebSourceDescriptors.forTypeOrError(SourceType.ABS)

    @Test
    fun `ABS add title is Add Audiobookshelf`() {
        assertEquals("Add Audiobookshelf", absDescriptor.addSourceCopyFor(ServerType.AUDIOBOOKSHELF)!!.addTitle)
    }

    @Test
    fun `ABS edit title is Edit Audiobookshelf`() {
        assertEquals("Edit Audiobookshelf", absDescriptor.addSourceCopyFor(ServerType.AUDIOBOOKSHELF)!!.editTitle)
    }

    @Test
    fun `ABS remove label is Remove source`() {
        assertEquals("Remove source", absDescriptor.addSourceCopyFor(ServerType.AUDIOBOOKSHELF)!!.removeLabel)
    }

    @Test
    fun `Storyteller strings unchanged kept for issue 441`() {
        val copy = absDescriptor.addSourceCopyFor(ServerType.STORYTELLER_SERVICE)!!
        assertEquals("Add Storyteller", copy.addTitle)
        assertEquals("Edit Storyteller", copy.editTitle)
        assertEquals("Remove Storyteller", copy.removeLabel)
    }
}
