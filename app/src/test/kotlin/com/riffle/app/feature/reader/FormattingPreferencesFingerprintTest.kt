package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormattingPreferencesFingerprintTest {

    @Test
    fun `fingerprint is stable across calls for same prefs`() {
        val prefs = FormattingPreferences()
        assertEquals(formattingPreferencesFingerprint(prefs), formattingPreferencesFingerprint(prefs))
    }

    @Test
    fun `fingerprint differs when font family changes`() {
        val a = FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif)
        val b = FormattingPreferences(fontFamily = ReaderFontFamily.Monospace)
        assertNotEquals(formattingPreferencesFingerprint(a), formattingPreferencesFingerprint(b))
    }

    @Test
    fun `fingerprint differs when font size changes`() {
        val a = FormattingPreferences(fontSize = 1.0f)
        val b = FormattingPreferences(fontSize = 1.5f)
        assertNotEquals(formattingPreferencesFingerprint(a), formattingPreferencesFingerprint(b))
    }

    @Test
    fun `fingerprint is a non-empty hex string`() {
        val fp = formattingPreferencesFingerprint(FormattingPreferences())
        assert(fp.isNotEmpty())
        assert(fp.all { it.isDigit() || it in 'a'..'f' }) { "Expected lowercase hex: $fp" }
    }
}
