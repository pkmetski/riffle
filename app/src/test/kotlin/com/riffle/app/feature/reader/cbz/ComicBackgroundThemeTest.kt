package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.asComicBackgroundTheme
import com.riffle.core.domain.comic.resolveComicBackgroundTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicBackgroundThemeTest {

    @Test
    fun `concrete comic background ignores auto resolved reader theme`() {
        assertEquals(
            ReaderTheme.Sepia,
            ReaderTheme.Sepia.resolveComicBackgroundTheme(ReaderTheme.Dark),
        )
    }

    @Test
    fun `legacy dim comic background is treated as dark`() {
        assertEquals(ReaderTheme.Dark, ReaderTheme.DarkDim.asComicBackgroundTheme())
    }

    @Test
    fun `auto comic background uses resolved reader theme after comic normalization`() {
        assertEquals(
            ReaderTheme.Dark,
            ReaderTheme.Auto.resolveComicBackgroundTheme(ReaderTheme.DarkDim),
        )
    }
}
