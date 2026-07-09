package com.riffle.app.feature.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ABS-variant AddSource copy after the #435 Server → Source rename. If any of
 * these assertions flip, the fix has been reverted line-for-line. Storyteller/WebDAV
 * strings are pinned untouched — Storyteller's rename is #441's job, WebDAV is a Service.
 */
class AddSourceCopyTest {
    @Test
    fun `ABS add title is Add Audiobookshelf`() {
        assertEquals("Add Audiobookshelf", screenTitle(AddSourceBackend.AUDIOBOOKSHELF, isEditing = false))
    }

    @Test
    fun `ABS edit title is Edit Audiobookshelf`() {
        assertEquals("Edit Audiobookshelf", screenTitle(AddSourceBackend.AUDIOBOOKSHELF, isEditing = true))
    }

    @Test
    fun `ABS remove label is Remove source`() {
        assertEquals("Remove source", removeButtonLabel(AddSourceBackend.AUDIOBOOKSHELF))
    }

    @Test
    fun `Storyteller strings unchanged (kept for #441)`() {
        assertEquals("Add Storyteller", screenTitle(AddSourceBackend.STORYTELLER, isEditing = false))
        assertEquals("Edit Storyteller", screenTitle(AddSourceBackend.STORYTELLER, isEditing = true))
        assertEquals("Remove Storyteller", removeButtonLabel(AddSourceBackend.STORYTELLER))
    }

    @Test
    fun `WebDAV strings unchanged (Service not Source)`() {
        assertEquals("Add WebDAV", screenTitle(AddSourceBackend.WEBDAV, isEditing = false))
        assertEquals("Edit WebDAV", screenTitle(AddSourceBackend.WEBDAV, isEditing = true))
        assertEquals("Disable sync", removeButtonLabel(AddSourceBackend.WEBDAV))
    }
}
