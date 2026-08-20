package com.riffle.app.feature.settings

import com.riffle.app.feature.settings.sections.comicDisplaySummary
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicDisplaySummaryTest {

    @Test fun `panel view off shows Panel View off regardless of overflow`() {
        assertEquals(
            "Dark background · Panel View off",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SPLIT)),
        )
    }

    @Test fun `panel view on with SPLIT shows Split`() {
        assertEquals(
            "Dark background · Panel View · Split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SPLIT)),
        )
    }

    @Test fun `panel view on with SMART_SPLIT shows Smart split`() {
        assertEquals(
            "Dark background · Panel View · Smart split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SMART_SPLIT)),
        )
    }

    @Test fun `panel view on with OFF shows No split`() {
        // PanelOverflowBehavior.OFF is now the user-selectable "No split" option in the
        // overflow radio group, so the summary must reflect that label.
        assertEquals(
            "Dark background · Panel View · No split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.OFF)),
        )
    }

    @Test fun `reading progress on appends to panel view summary`() {
        assertEquals(
            "Dark background · Panel View · Split · Reading progress",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SPLIT, showChapterMap = true)),
        )
    }

    @Test fun `reading progress on panel view also off`() {
        assertEquals(
            "Dark background · Panel View off · Reading progress",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, showChapterMap = true)),
        )
    }

    @Test fun `page numbers on appends after reading progress`() {
        assertEquals(
            "Dark background · Panel View off · Reading progress · Page numbers",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, showChapterMap = true, showPageProgress = true)),
        )
    }

    @Test fun `page progress on without chapter map does not append`() {
        assertEquals(
            "Dark background · Panel View off",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, showChapterMap = false, showPageProgress = true)),
        )
    }

    @Test fun `background theme starts comic display summary`() {
        assertEquals(
            "Sepia background · Panel View off",
            comicDisplaySummary(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Sepia)),
        )
    }

    @Test fun `auto background starts comic display summary`() {
        assertEquals(
            "Auto background · Panel View off",
            comicDisplaySummary(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Auto)),
        )
    }
}
