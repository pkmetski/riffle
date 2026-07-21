package com.riffle.app.feature.reader

import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the popup pre-selection rules (Bug 2, 2026-07-19): a pending draft must pre-select the
 * per-book last-used colour + emphasis so a dismiss-without-explicit-pick auto-commits in the
 * user's remembered state — not the ∅ crossed-circle that used to show for every fresh selection.
 *
 * These assertions would flip red if line 3082 of EpubReaderScreen.kt were reverted to
 * `if (isDraft || …) null` (the pre-fix behaviour).
 */
class DraftPopupSelectionTest {

    @Test
    fun draft_preSelectsLastUsedColor_whenNotNone() {
        val result = resolveDraftPopupSelection(
            isDraft = true,
            persistedColor = null,
            persistedEmphasisStyles = emptySet(),
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
            lastUsedEmphasisStyles = emptySet(),
        )
        assertEquals(HighlightColor.GREEN, result.selectedColor)
        assertEquals(emptySet<EmphasisStyle>(), result.emphasisStyles)
    }

    @Test
    fun draft_preSelectsNone_whenLastUsedIsNone() {
        val result = resolveDraftPopupSelection(
            isDraft = true,
            persistedColor = null,
            persistedEmphasisStyles = emptySet(),
            lastUsedHighlightColor = HighlightColor.YELLOW,
            lastUsedColorIsNone = true,
            lastUsedEmphasisStyles = emptySet(),
        )
        assertNull(result.selectedColor)
    }

    @Test
    fun draft_preSelectsLastUsedEmphasisStyles() {
        val preset = setOf(EmphasisStyle.BOLD, EmphasisStyle.ITALIC)
        val result = resolveDraftPopupSelection(
            isDraft = true,
            persistedColor = null,
            persistedEmphasisStyles = emptySet(),
            lastUsedHighlightColor = HighlightColor.YELLOW,
            lastUsedColorIsNone = false,
            lastUsedEmphasisStyles = preset,
        )
        assertEquals(preset, result.emphasisStyles)
    }

    @Test
    fun persistedRow_reflectsStoredColor() {
        val result = resolveDraftPopupSelection(
            isDraft = false,
            persistedColor = HighlightColor.BLUE.token,
            persistedEmphasisStyles = setOf(EmphasisStyle.UNDERLINE),
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
            lastUsedEmphasisStyles = setOf(EmphasisStyle.BOLD),
        )
        assertEquals(HighlightColor.BLUE, result.selectedColor)
        assertEquals(setOf(EmphasisStyle.UNDERLINE), result.emphasisStyles)
    }

    @Test
    fun persistedRow_withEmptyColorShowsNone() {
        // ADR 0046 §4: after ∅ the row's color is empty; pass null so the swatch row highlights ∅.
        // (Passing empty through HighlightColor.fromToken would fall back to DEFAULT and show yellow.)
        val result = resolveDraftPopupSelection(
            isDraft = false,
            persistedColor = "",
            persistedEmphasisStyles = emptySet(),
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
            lastUsedEmphasisStyles = emptySet(),
        )
        assertNull(result.selectedColor)
    }

    // ---- Auto-commit predicate (dismiss behaviour) ----

    @Test
    fun autoCommit_whenColorRemembered() {
        assertEquals(true, shouldAutoCommitDraftOnDismiss(lastUsedColorIsNone = false, lastUsedEmphasisStyles = emptySet()))
    }

    @Test
    fun autoCommit_whenEmphasisRemembered_evenIfColorIsNone() {
        assertEquals(true, shouldAutoCommitDraftOnDismiss(lastUsedColorIsNone = true, lastUsedEmphasisStyles = setOf(EmphasisStyle.BOLD)))
    }

    @Test
    fun discard_whenNothingRemembered() {
        // Only case where dismiss discards the draft entirely — ∅ preset + no emphasis preset.
        assertEquals(false, shouldAutoCommitDraftOnDismiss(lastUsedColorIsNone = true, lastUsedEmphasisStyles = emptySet()))
    }

    @Test
    fun persistedRow_ignoresLastUsedState() {
        // Regression: persisted-row pre-selection MUST NOT leak the per-book last-used state —
        // that would show a chip active when no matching emphasis row exists.
        val result = resolveDraftPopupSelection(
            isDraft = false,
            persistedColor = HighlightColor.YELLOW.token,
            persistedEmphasisStyles = emptySet(),
            lastUsedHighlightColor = HighlightColor.GREEN,
            lastUsedColorIsNone = false,
            lastUsedEmphasisStyles = setOf(EmphasisStyle.BOLD),
        )
        assertEquals(HighlightColor.YELLOW, result.selectedColor)
        assertEquals(emptySet<EmphasisStyle>(), result.emphasisStyles)
    }
}
