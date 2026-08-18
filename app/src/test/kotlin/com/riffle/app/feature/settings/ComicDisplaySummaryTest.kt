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

    @Test fun `panel view on with legacy OFF shows Panel View on`() {
        assertEquals(
            "Panel View on",
            comicDisplaySummary(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.OFF)),
        )
    }
}
