package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit

class PdfiumPreferencesMapperTest {
    @Test
    fun `axis and fit are always vertical width regardless of orientation`() {
        ReaderOrientation.entries.forEach { orientation ->
            val prefs = FormattingPreferences(orientation = orientation)
            val out = prefs.toPdfiumPreferences()
            assertEquals(Axis.VERTICAL, out.scrollAxis)
            assertEquals(Fit.WIDTH, out.fit)
        }
    }

    @Test
    fun `readingProgression is null so Readium uses publication metadata`() {
        val out = FormattingPreferences().toPdfiumPreferences()
        assertNull(out.readingProgression)
    }

    @Test
    fun `default margins emit null pageSpacing so Readium's default is preserved`() {
        val out = FormattingPreferences(margins = 1.0f).toPdfiumPreferences()
        assertNull(out.pageSpacing)
    }

    @Test
    fun `above-default margins scale linearly around zero`() {
        val out = FormattingPreferences(margins = 2.0f).toPdfiumPreferences()
        assertEquals(16.0, out.pageSpacing!!, 0.001)
    }

    @Test
    fun `below-default margins produce negative spacing so users can tighten`() {
        val out = FormattingPreferences(margins = 0.5f).toPdfiumPreferences()
        assertEquals(-8.0, out.pageSpacing!!, 0.001)
    }
}
