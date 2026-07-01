package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
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
    fun `margins multiplier maps to pageSpacing in dp scale`() {
        val prefs = FormattingPreferences(margins = 1.5f)
        val out = prefs.toPdfiumPreferences()
        assertEquals(72.0, out.pageSpacing!!, 0.001)
    }
}
