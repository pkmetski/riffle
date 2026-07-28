package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.HighlightColor

/**
 * Per-book "last-used" highlight colour for user-created annotations.
 *
 * Every time the user picks a colour on a highlight (at creation or on recolour), that colour
 * becomes the default for the next new highlight IN THE SAME BOOK, so the colour-picker opens
 * with it already selected on that book. Books that the user has not picked a colour on yet
 * fall back to [HighlightColor.DEFAULT] (the first entry in the palette). Readaloud has its own
 * separate [ReadaloudPreferencesStore]; the two never share state.
 */
interface HighlightColorPreferencesStore {
    fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor>
    suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor)

    /** ADR 0046 §4: `∅` is a valid last-pick — a book where the user annotates only with
     *  emphasis wants the next new annotation to open with `∅` selected, not with the last
     *  colour they happened to pick before switching to emphasis-only. Emits `true` once the
     *  user has tapped `∅` on this book at least once, and stays true until they pick a real
     *  colour swatch. Default: `false` (books the user has never annotated fall back to a
     *  yellow default per the existing colour preference). */
    fun lastUsedIsNone(sourceId: String, itemId: String): Flow<Boolean> =
        kotlinx.coroutines.flow.flowOf(false)

    /** Set on ∅ (true) or on any real-colour pick (false). */
    suspend fun setLastUsedIsNone(sourceId: String, itemId: String, value: Boolean) = Unit
}
