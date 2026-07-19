package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.EmphasisStyle

/**
 * Per-book "last-used" emphasis styles set for user-created emphasis annotations (ADR 0046).
 *
 * Mirrors [HighlightColorPreferencesStore]: every time the user picks emphasis styles on a
 * highlight (via a chip toggle), the resulting set becomes the default for the NEXT
 * user-initiated annotate gesture IN THE SAME BOOK — matching how the sheet already remembers
 * the last-picked colour. Combined with the `∅` (no-color) swatch, this lets a user who
 * consistently uses "italicise the important passages" get one-tap emphasis without repainting
 * the whole sheet every time.
 *
 * Books the user has not picked emphasis on yet fall back to an empty set (no emphasis applied
 * on next annotate). Legacy tokens outside the current [EmphasisStyle] set are dropped
 * gracefully.
 */
interface EmphasisPreferencesStore {
    fun lastUsedStyles(sourceId: String, itemId: String): Flow<Set<EmphasisStyle>>
    suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>)
}
