package com.riffle.app.feature.source.localfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the empty-state scan copy against re-specialization. The message used to name EPUB and
 * PDF explicitly, so when CBZ support landed the string silently went stale. If someone reverts
 * to naming individual formats, both assertions below flip red.
 */
class EmptyScanMessageTest {

    @Test
    fun `message stays format-agnostic`() {
        assertEquals("No supported books were found in that folder.", EMPTY_SCAN_MESSAGE)
    }

    @Test
    fun `message names no specific format`() {
        val lower = EMPTY_SCAN_MESSAGE.lowercase()
        for (token in listOf("epub", "pdf", "cbz", "cbr", "mobi", "azw")) {
            assertFalse(
                "EMPTY_SCAN_MESSAGE must not name specific formats (found '$token'): $EMPTY_SCAN_MESSAGE",
                lower.contains(token),
            )
        }
    }
}
