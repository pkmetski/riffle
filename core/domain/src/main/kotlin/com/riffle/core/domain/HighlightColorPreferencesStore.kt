package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

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
}
