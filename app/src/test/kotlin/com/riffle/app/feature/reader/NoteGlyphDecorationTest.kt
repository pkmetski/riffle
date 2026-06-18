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
    fun `noteGlyphTemplate stylesheet positions glyph in the left gutter`() {
        // The ::before pseudo-element must be placed to the left of the bounding box.
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must contain '::before'", stylesheet.contains("::before"))
        assertTrue("stylesheet must position glyph left of bounds via 'right: 100%'",
            stylesheet.contains("right: 100%"))
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
