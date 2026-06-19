@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.html.HtmlDecorationTemplate

class NoteGlyphDecorationTest {

    @Test
    fun `two NoteGlyphStyle instances are equal`() {
        assertEquals(NoteGlyphStyle(), NoteGlyphStyle())
    }

    @Test
    fun `NoteGlyphStyle is not equal to HighlightTintStyle`() {
        assertNotEquals(NoteGlyphStyle(), HighlightTintStyle(0xFF000000.toInt()))
    }

    @Test
    fun `noteGlyphTemplate uses BOUNDS layout`() {
        val template = noteGlyphTemplate()
        assertEquals(HtmlDecorationTemplate.Layout.BOUNDS, template.layout)
    }

    @Test
    fun `noteGlyphTemplate element has data-activable on inner icon div`() {
        // Readium hit-tests using data-activable='1' elements; without it, it falls back to
        // D.children (the outer BOUNDS div covering the text selection), which misses the gutter.
        assertTrue("inner icon div must carry data-activable='1' so Readium uses its gutter rect",
            NOTE_GLYPH_ELEMENT_HTML.contains("data-activable=\"1\""))
    }

    @Test
    fun `noteGlyphTemplate stylesheet targets inner icon class for tap-bubbling element`() {
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must style the inner icon div class",
            stylesheet.contains("riffle-note-glyph-icon"))
        assertTrue("stylesheet must not use ::before — pseudo-elements outside the hit area",
            !stylesheet.contains("::before"))
    }

    @Test
    fun `noteGlyphTemplate stylesheet positions icon div in the left gutter`() {
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must position icon left of selection bounds",
            stylesheet.contains("left: -28px"))
    }

    @Test
    fun `noteGlyphTemplate stylesheet uses mask-image for theme-aware colouring`() {
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must use -webkit-mask-image so glyph inherits currentColor",
            stylesheet.contains("-webkit-mask-image"))
        assertTrue("mask image must reference the SVG data URI",
            stylesheet.contains("data:image/svg+xml"))
        assertTrue("glyph must use currentColor background to be theme-aware",
            stylesheet.contains("background-color: currentColor"))
    }

    // NOTE: Parcelable round-trip test is omitted here because android.os.Parcel is a JVM stub.
    // It will be added as an androidTest in Task 2's harness run (on-device verification).
}
