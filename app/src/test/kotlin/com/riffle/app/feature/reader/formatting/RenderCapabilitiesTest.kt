package com.riffle.app.feature.reader.formatting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCapabilitiesTest {
    @Test
    fun `epub declares full capabilities`() {
        val caps = RenderCapabilities.EPUB
        assertTrue(caps.supportsFontFamily)
        assertTrue(caps.supportsTextTypography)
        assertTrue(caps.supportsPublisherStyles)
        assertTrue(caps.supportsTheme)
        assertTrue(caps.supportsReadingModeSwitch)
        assertTrue(caps.supportsDoublePage)
        assertTrue(caps.supportsReadingProgressLabels)
    }

    @Test
    fun `pdf disables font family, publisher styles, mode switch, and double page`() {
        val caps = RenderCapabilities.PDF
        assertFalse(caps.supportsFontFamily)
        assertFalse(caps.supportsTextTypography)
        assertFalse(caps.supportsPublisherStyles)
        assertFalse(caps.supportsTheme)
        assertFalse(caps.supportsReadingModeSwitch)
        assertFalse(caps.supportsDoublePage)
        assertFalse(caps.supportsReadingProgressLabels)
    }
}
