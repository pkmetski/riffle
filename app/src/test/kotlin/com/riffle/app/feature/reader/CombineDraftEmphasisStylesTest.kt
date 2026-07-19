package com.riffle.app.feature.reader

import com.riffle.core.domain.EmphasisStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR 0046 §4: [combineDraftEmphasisStyles] must TOGGLE a tapped chip that's already in the
 * per-book preset (the sheet pre-selected it, so tapping again means the user wants it off),
 * ADD one that isn't in the preset yet, and PASS THROUGH the preset unchanged when the tap
 * came from a colour swatch (null chip).
 *
 * Reverting to set union (`preset + tapped`) flips the toggle-off test red — a chip that's
 * pre-selected can never be deselected on the draft sheet, which is exactly the
 * "annotation was created with bold I tried to turn off" symptom.
 */
class CombineDraftEmphasisStylesTest {

    private val BOLD = EmphasisStyle.BOLD
    private val ITALIC = EmphasisStyle.ITALIC

    @Test
    fun `null tap carries preset through unchanged`() {
        assertEquals(setOf(BOLD, ITALIC), combineDraftEmphasisStyles(setOf(BOLD, ITALIC), null))
    }

    @Test
    fun `null tap on empty preset stays empty`() {
        assertEquals(emptySet<EmphasisStyle>(), combineDraftEmphasisStyles(emptySet(), null))
    }

    @Test
    fun `tap on new chip adds it to the preset`() {
        assertEquals(setOf(BOLD), combineDraftEmphasisStyles(emptySet(), BOLD))
    }

    @Test
    fun `tap on a chip already in the preset TOGGLES it off`() {
        assertEquals(emptySet<EmphasisStyle>(), combineDraftEmphasisStyles(setOf(BOLD), BOLD))
    }

    @Test
    fun `tap on one of multiple preset chips removes only that chip`() {
        assertEquals(setOf(ITALIC), combineDraftEmphasisStyles(setOf(BOLD, ITALIC), BOLD))
    }

    @Test
    fun `tap adds a distinct chip on top of the preset`() {
        assertEquals(setOf(BOLD, ITALIC), combineDraftEmphasisStyles(setOf(BOLD), ITALIC))
    }
}
