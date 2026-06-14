package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class BookFormattingOverridesTest {

    private val global = FormattingPreferences(
        fontSize = 1.0f,
        theme = ReaderTheme.Light,
        fontFamily = ReaderFontFamily.Serif,
        lineSpacing = 1.2f,
        margins = 1.0f,
        orientation = ReaderOrientation.Horizontal,
        showChapterMap = true,
        doublePageSpread = false,
        justifyText = false,
    )

    @Test
    fun `empty overrides applyTo returns global verbatim`() {
        val effective = BookFormattingOverrides().applyTo(global)
        assertEquals(global, effective)
    }

    @Test
    fun `isEmpty is true only when every field is null`() {
        assertTrue(BookFormattingOverrides().isEmpty)
        assertFalse(BookFormattingOverrides(fontSize = 1.3f).isEmpty)
        assertFalse(BookFormattingOverrides(justifyText = true).isEmpty)
        assertFalse(BookFormattingOverrides(showCurrentChapterLabel = true).isEmpty)
    }

    @Test
    fun `applyTo threads showCurrentChapterLabel`() {
        val effective = BookFormattingOverrides(showCurrentChapterLabel = true).applyTo(global)
        assertTrue(effective.showCurrentChapterLabel)
        // Untouched on the global side stays false (the default).
        assertFalse(BookFormattingOverrides().applyTo(global).showCurrentChapterLabel)
    }

    @Test
    fun `applyTo substitutes only non-null fields`() {
        val overrides = BookFormattingOverrides(
            fontSize = 1.5f,
            theme = ReaderTheme.Dark,
        )
        val effective = overrides.applyTo(global)
        assertEquals(1.5f, effective.fontSize)
        assertEquals(ReaderTheme.Dark, effective.theme)
        // Untouched fields fall through to global.
        assertEquals(global.lineSpacing, effective.lineSpacing)
        assertEquals(global.fontFamily, effective.fontFamily)
        assertEquals(global.justifyText, effective.justifyText)
    }

    @Test
    fun `withChanges records only the fields that differ from previous`() {
        val previous = global
        val new = global.copy(fontSize = 1.4f, justifyText = true)
        val overrides = BookFormattingOverrides().withChanges(previous, new)
        assertEquals(1.4f, overrides.fontSize)
        assertEquals(true, overrides.justifyText)
        assertNull(overrides.theme)
        assertNull(overrides.lineSpacing)
        assertNull(overrides.margins)
    }

    @Test
    fun `withChanges leaves untouched override fields alone`() {
        // Pre-existing override on theme; user changes only fontSize.
        val existing = BookFormattingOverrides(theme = ReaderTheme.Dark)
        val previous = existing.applyTo(global)
        val new = previous.copy(fontSize = 1.6f)
        val updated = existing.withChanges(previous, new)
        assertEquals(ReaderTheme.Dark, updated.theme) // preserved
        assertEquals(1.6f, updated.fontSize) // newly recorded
    }

    @Test
    fun `applyTo always threads global themeSchedule (no per-book override)`() {
        val customSchedule = ThemeSchedule(
            dayStart = LocalTime.of(6, 30),
            nightStart = LocalTime.of(19, 45),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        val globalWithSchedule = global.copy(themeSchedule = customSchedule)
        val effective = BookFormattingOverrides(theme = ReaderTheme.Auto)
            .applyTo(globalWithSchedule)
        assertEquals(customSchedule, effective.themeSchedule)
    }

    @Test
    fun `withChanges does not record a field when new equals previous`() {
        val previous = global
        val new = global.copy() // identical
        val updated = BookFormattingOverrides().withChanges(previous, new)
        assertTrue(updated.isEmpty)
    }

    @Test
    fun `isEmpty is false when showReadingTimeEstimate is set`() {
        assertFalse(BookFormattingOverrides(showReadingTimeEstimate = false).isEmpty)
    }

    @Test
    fun `applyTo threads showReadingTimeEstimate`() {
        val effective = BookFormattingOverrides(showReadingTimeEstimate = false).applyTo(global)
        assertFalse(effective.showReadingTimeEstimate)
        // Global default is true
        assertTrue(BookFormattingOverrides().applyTo(global).showReadingTimeEstimate)
    }

    @Test
    fun `withChanges records showReadingTimeEstimate when changed`() {
        val prev = global
        val new = global.copy(showReadingTimeEstimate = false)
        val overrides = BookFormattingOverrides().withChanges(prev, new)
        assertEquals(false, overrides.showReadingTimeEstimate)
        assertNull(overrides.fontSize)
    }
}
