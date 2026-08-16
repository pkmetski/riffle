package com.riffle.core.domain.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookComicFormattingOverridesTest {

    private val global = ComicFormattingPreferences(
        panelViewOn = false,
        panelOverflow = PanelOverflowBehavior.SPLIT,
    )

    @Test fun `applyTo returns global when overrides are all null`() {
        val result = BookComicFormattingOverrides().applyTo(global)
        assertEquals(global, result)
    }

    @Test fun `applyTo overrides panelViewOn when set`() {
        val result = BookComicFormattingOverrides(panelViewOn = true).applyTo(global)
        assertTrue(result.panelViewOn)
        assertEquals(PanelOverflowBehavior.SPLIT, result.panelOverflow)
    }

    @Test fun `applyTo overrides panelOverflow when set`() {
        val result = BookComicFormattingOverrides(panelOverflow = PanelOverflowBehavior.AUTO_ROTATE).applyTo(global)
        assertFalse(result.panelViewOn)
        assertEquals(PanelOverflowBehavior.AUTO_ROTATE, result.panelOverflow)
    }

    @Test fun `applyTo can override both fields simultaneously`() {
        val result = BookComicFormattingOverrides(
            panelViewOn = true,
            panelOverflow = PanelOverflowBehavior.OFF,
        ).applyTo(global)
        assertTrue(result.panelViewOn)
        assertEquals(PanelOverflowBehavior.OFF, result.panelOverflow)
    }

    @Test fun `isEmpty returns true when all fields null`() {
        assertTrue(BookComicFormattingOverrides().isEmpty())
    }

    @Test fun `isEmpty returns false when any field non-null`() {
        assertFalse(BookComicFormattingOverrides(panelViewOn = true).isEmpty())
        assertFalse(BookComicFormattingOverrides(panelOverflow = PanelOverflowBehavior.OFF).isEmpty())
    }
}
