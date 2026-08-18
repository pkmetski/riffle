package com.riffle.app.feature.settings

import com.riffle.app.feature.settings.sections.comicDisplaySummary
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicDisplaySummaryTest {

    @Test fun `panel view off shows Panel View off regardless of overflow`() {
        assertEquals(
            "Panel View off",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SPLIT)),
        )
    }

    @Test fun `panel view on with SPLIT shows Split`() {
        assertEquals(
            "Panel View · Split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SPLIT)),
        )
    }

    @Test fun `panel view on with SMART_SPLIT shows Smart split`() {
        assertEquals(
            "Panel View · Smart split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SMART_SPLIT)),
        )
    }

    @Test fun `panel view on with OFF shows No split`() {
        // PanelOverflowBehavior.OFF is now the user-selectable "No split" option in the
        // overflow radio group, so the summary must reflect that label.
        assertEquals(
            "Panel View · No split",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.OFF)),
        )
    }

    @Test fun `chapter map on appends to panel view summary`() {
        assertEquals(
            "Panel View · Split · Chapter map",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SPLIT, showChapterMap = true)),
        )
    }

    @Test fun `chapter map on panel view also off`() {
        assertEquals(
            "Panel View off · Chapter map",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = false, showChapterMap = true)),
        )
    }
}
