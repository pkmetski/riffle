package com.riffle.app.feature.reader

import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTintTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF
    private fun rgb(argb: Int) = argb and 0x00FFFFFF

    @Test
    fun `dark themes use the stronger alpha, light and sepia the lighter one`() {
        val argb = HighlightColor.GREEN.argb
        assertEquals(0x73, alpha(tintForTheme(argb, ReaderTheme.Dark)))
        assertEquals(0x73, alpha(tintForTheme(argb, ReaderTheme.DarkDim)))
        assertEquals(0x4D, alpha(tintForTheme(argb, ReaderTheme.Light)))
        assertEquals(0x4D, alpha(tintForTheme(argb, ReaderTheme.Sepia)))
    }

    @Test
    fun `tint preserves the base hue and only swaps the alpha channel`() {
        val tint = HighlightColor.PINK.readerTint(ReaderTheme.Dark)
        assertEquals(rgb(HighlightColor.PINK.argb), rgb(tint))
        assertEquals(0x73, alpha(tint))
    }
}
