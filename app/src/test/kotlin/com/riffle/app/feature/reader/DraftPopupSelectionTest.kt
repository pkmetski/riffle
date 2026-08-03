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

    // ---- commitDraft phantom-empty guard ----

    @Test
    fun phantomGuard_discardsWhenColorEmptyAndStylesEmpty() {
        // Regression: tapping ∅ on a draft with no emphasis preset must NOT persist a phantom
        // empty annotation row. This assertion flips red if the shouldDiscardPhantomDraftCommit
        // guard in commitDraft is removed or the condition is inverted.
        assertEquals(true, shouldDiscardPhantomDraftCommit(initialColor = "", combinedStyles = emptySet()))
    }

    @Test
    fun phantomGuard_allowsCommitWhenColorEmpty_butStylesPresent() {
        // ∅ colour + BOLD emphasis is a valid emphasis-only annotation — must not be discarded.
        assertEquals(false, shouldDiscardPhantomDraftCommit(initialColor = "", combinedStyles = setOf(EmphasisStyle.BOLD)))
    }

    @Test
    fun phantomGuard_allowsCommitWhenColorSet_stylesEmpty() {
        // Real colour + no emphasis is a standard highlight — must not be discarded.
        assertEquals(false, shouldDiscardPhantomDraftCommit(initialColor = "YELLOW", combinedStyles = emptySet()))
    }

    @Test
    fun phantomGuard_allowsCommitWhenNotePresent_evenWithEmptyColorAndStyles() {
        // Regression (2026-08-03): a draft committed FROM THE NOTE EDITOR carries a note — a
        // ∅-colour, no-emphasis annotation with a note is a valid row, not a phantom. Flips red
        // if commitDraft's guard stops considering the note.
        assertEquals(
            false,
            shouldDiscardPhantomDraftCommit(initialColor = "", combinedStyles = emptySet(), note = "my thought"),
        )
    }

    // ---- note-editor close on a draft ----

    @Test
    fun noteEditorClose_commitsWhenNotePresent_evenWithNoPreset() {
        // Regression (2026-08-03): typing a note on a fresh selection must ALWAYS create the
        // annotation, even when the per-book preset is ∅ + no emphasis (where a plain dismiss
        // would discard).
        assertEquals(
            true,
            shouldCommitDraftOnNoteEditorClose(note = "my thought", lastUsedColorIsNone = true, lastUsedEmphasisStyles = emptySet()),
        )
    }

    @Test
    fun noteEditorClose_withoutNote_followsDismissSemantics() {
        // No note → identical to dismissing the actions popup: preset commit when a colour or
        // emphasis preset exists, discard when the preset is ∅ + nothing.
        assertEquals(
            true,
            shouldCommitDraftOnNoteEditorClose(note = null, lastUsedColorIsNone = false, lastUsedEmphasisStyles = emptySet()),
        )
        assertEquals(
            false,
            shouldCommitDraftOnNoteEditorClose(note = null, lastUsedColorIsNone = true, lastUsedEmphasisStyles = emptySet()),
        )
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
