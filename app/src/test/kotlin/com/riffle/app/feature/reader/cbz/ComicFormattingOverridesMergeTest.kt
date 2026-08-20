package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.PanelOverflowBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicFormattingOverridesMergeTest {

    @Test
    fun `merge applies backgroundTheme patch without dropping existing comic options`() {
        val current = BookComicFormattingOverrides(
            panelViewOn = true,
            panelOverflow = PanelOverflowBehavior.SMART_SPLIT,
            panelAnimationSpeedMs = 450,
            showChapterMap = true,
            showPageProgress = false,
        )

        val merged = mergeComicFormattingOverrides(
            current = current,
            patch = BookComicFormattingOverrides(backgroundTheme = ReaderTheme.Sepia),
        )

        assertEquals(ReaderTheme.Sepia, merged.backgroundTheme)
        assertEquals(true, merged.panelViewOn)
        assertEquals(PanelOverflowBehavior.SMART_SPLIT, merged.panelOverflow)
        assertEquals(450, merged.panelAnimationSpeedMs)
        assertEquals(true, merged.showChapterMap)
        assertEquals(false, merged.showPageProgress)
    }

    @Test
    fun `merge preserves existing backgroundTheme when patch leaves it unset`() {
        val current = BookComicFormattingOverrides(backgroundTheme = ReaderTheme.Dark)

        val merged = mergeComicFormattingOverrides(
            current = current,
            patch = BookComicFormattingOverrides(panelAnimationSpeedMs = 0),
        )

        assertEquals(ReaderTheme.Dark, merged.backgroundTheme)
        assertEquals(0, merged.panelAnimationSpeedMs)
    }
}
