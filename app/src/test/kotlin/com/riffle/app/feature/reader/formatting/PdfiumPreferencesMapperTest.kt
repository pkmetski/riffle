package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit

class PdfiumPreferencesMapperTest {
    @Test
    fun `horizontal orientation maps to horizontal axis and contain fit`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Horizontal)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.HORIZONTAL, out.scrollAxis)
        assertEquals(Fit.CONTAIN, out.fit)
    }

    @Test
    fun `vertical orientation maps to vertical axis and width fit`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Vertical)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.VERTICAL, out.scrollAxis)
        assertEquals(Fit.WIDTH, out.fit)
    }

    @Test
    fun `continuous orientation maps like vertical`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Continuous)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.VERTICAL, out.scrollAxis)
        assertEquals(Fit.WIDTH, out.fit)
    }

    @Test
    fun `margins multiplier maps to pageSpacing in dp scale`() {
        val prefs = FormattingPreferences(margins = 1.5f)
        val out = prefs.toPdfiumPreferences()
        assertEquals(12.0, out.pageSpacing!!, 0.001)
    }
}
