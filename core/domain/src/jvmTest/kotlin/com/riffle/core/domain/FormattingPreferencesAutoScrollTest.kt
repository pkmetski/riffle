package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingPreferencesAutoScrollTest {

    @Test
    fun `default autoScrollWpm is 250`() {
        assertEquals(250, FormattingPreferences().autoScrollWpm)
        assertEquals(250, FormattingPreferences.DEFAULT_AUTO_SCROLL_WPM)
    }

    @Test
    fun `copy with new autoScrollWpm preserves other fields`() {
        val base = FormattingPreferences(fontSize = 1.3f)
        val updated = base.copy(autoScrollWpm = 300)
        assertEquals(1.3f, updated.fontSize, 0f)
        assertEquals(300, updated.autoScrollWpm)
    }
}
