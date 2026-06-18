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
    fun `noteGlyphTemplate stylesheet references the SVG data URI`() {
        val stylesheet = noteGlyphTemplate().stylesheet ?: ""
        assertTrue("stylesheet must include SVG data URI background-image",
            stylesheet.contains("data:image/svg+xml"))
    }

    // NOTE: Parcelable round-trip test is omitted here because android.os.Parcel is a JVM stub.
    // It will be added as an androidTest in Task 2's harness run (on-device verification).
}
