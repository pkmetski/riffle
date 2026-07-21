package com.riffle.app.feature.reader

import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor

/**
 * Pure derivation of the highlight-actions popup's pre-selected state (swatch + emphasis chips).
 *
 * Extracted from [EpubReaderScreen]'s popup block so the selection rules are JVM-testable without
 * standing up Compose. The rules — one place, one truth:
 *
 * - **Persisted annotation** (`isDraft = false`): `selectedColor` reflects the row's stored color;
 *   empty color → `null` (∅ shown); `emphasisStyles` reflects the sibling emphasis row at the same
 *   CFI, empty otherwise.
 * - **Pending draft** (`isDraft = true`): ADR 0046 §4 — pre-select the per-book last-used state so
 *   dismissing the sheet with no explicit pick auto-commits the "last color" annotation the user
 *   is used to (see [com.riffle.app.feature.reader.session.AnnotationSession.dismissHighlightActions]).
 *   `selectedColor` = last-used color (or `null` when the last pick was ∅);
 *   `emphasisStyles` = last-used emphasis set.
 */
internal fun resolveDraftPopupSelection(
    isDraft: Boolean,
    persistedColor: String?,
    persistedEmphasisStyles: Set<EmphasisStyle>,
    lastUsedHighlightColor: HighlightColor,
    lastUsedColorIsNone: Boolean,
    lastUsedEmphasisStyles: Set<EmphasisStyle>,
): DraftPopupSelection {
    if (isDraft) {
        val color = if (lastUsedColorIsNone) null else lastUsedHighlightColor
        return DraftPopupSelection(selectedColor = color, emphasisStyles = lastUsedEmphasisStyles)
    }
    val color = if (persistedColor.isNullOrEmpty()) null else HighlightColor.fromToken(persistedColor)
    return DraftPopupSelection(selectedColor = color, emphasisStyles = persistedEmphasisStyles)
}

internal data class DraftPopupSelection(
    val selectedColor: HighlightColor?,
    val emphasisStyles: Set<EmphasisStyle>,
)

/**
 * Dismiss behaviour predicate for a pending draft (Bug 2, 2026-07-19).
 *
 * Returns `true` when the popup's dismiss should COMMIT the draft using the per-book last-used
 * preset (colour + emphasis) instead of discarding — the user's explicit spec was "dismissal
 * must only happen when no colour selected AND no formatting done either." When the last colour
 * pick was ∅ AND no emphasis preset is remembered, there is no meaningful preset to commit, so
 * the dismiss discards the draft (pre-fix behaviour).
 */
internal fun shouldAutoCommitDraftOnDismiss(
    lastUsedColorIsNone: Boolean,
    lastUsedEmphasisStyles: Set<EmphasisStyle>,
): Boolean = !lastUsedColorIsNone || lastUsedEmphasisStyles.isNotEmpty()
